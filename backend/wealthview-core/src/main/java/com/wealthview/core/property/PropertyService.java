package com.wealthview.core.property;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.core.audit.AuditEvent;
import com.wealthview.core.common.Entities;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.property.dto.CostSegAllocation;
import com.wealthview.core.property.dto.DepreciationScheduleResult;
import com.wealthview.core.property.dto.MonthlyCashFlowDetailEntry;
import com.wealthview.core.property.dto.MonthlyCashFlowEntry;
import com.wealthview.core.property.dto.PropertyExpenseRequest;
import com.wealthview.core.property.dto.PropertyExpenseResponse;
import com.wealthview.core.property.dto.PropertyRequest;
import com.wealthview.core.property.dto.PropertyResponse;
import com.wealthview.core.property.dto.PropertyIncomeRequest;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.PropertyExpenseEntity;
import com.wealthview.persistence.entity.PropertyIncomeEntity;
import com.wealthview.persistence.repository.IncomeSourceRepository;
import com.wealthview.persistence.repository.PropertyExpenseRepository;
import com.wealthview.persistence.repository.PropertyIncomeRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import com.wealthview.core.common.Money;

@Service
public class PropertyService {

    private static final Logger log = LoggerFactory.getLogger(PropertyService.class);
    private static final Set<String> VALID_PROPERTY_TYPES = Set.of("primary_residence", "investment", "vacation");
    private static final Set<String> VALID_DEPRECIATION_METHODS = Set.of("none", "straight_line", "cost_segregation");
    private static final Set<String> VALID_ASSET_CLASSES = Set.of("5yr", "7yr", "15yr", "27_5yr");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PropertyRepository propertyRepository;
    private final PropertyExpenseRepository expenseRepository;
    private final PropertyIncomeRepository incomeRepository;
    private final IncomeSourceRepository incomeSourceRepository;
    private final TenantRepository tenantRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final DepreciationCalculator depreciationCalculator;

    public PropertyService(PropertyRepository propertyRepository,
                           PropertyExpenseRepository expenseRepository,
                           PropertyIncomeRepository incomeRepository,
                           IncomeSourceRepository incomeSourceRepository,
                           TenantRepository tenantRepository,
                           ApplicationEventPublisher eventPublisher,
                           DepreciationCalculator depreciationCalculator) {
        this.propertyRepository = propertyRepository;
        this.expenseRepository = expenseRepository;
        this.incomeRepository = incomeRepository;
        this.incomeSourceRepository = incomeSourceRepository;
        this.tenantRepository = tenantRepository;
        this.eventPublisher = eventPublisher;
        this.depreciationCalculator = depreciationCalculator;
    }

    @Transactional
    public PropertyResponse create(UUID tenantId, PropertyRequest request) {
        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new InvalidSessionException("Session expired — please log in again"));

        validateLoanDetails(request);

        var mortgageBalance = request.mortgageBalance() != null
                ? request.mortgageBalance() : BigDecimal.ZERO;

        var property = new PropertyEntity(tenant, request.address(), request.purchasePrice(),
                request.purchaseDate(), request.currentValue(), mortgageBalance);
        applyLoanFields(property, request);
        applyPropertyType(property, request.propertyType());
        applyFinancialFields(property, request);
        applyDepreciationFields(property, request);
        property = propertyRepository.save(property);
        log.info("Property {} created for tenant {}", property.getId(), tenantId);
        eventPublisher.publishEvent(new AuditEvent(tenantId, null, "CREATE", "property",
                property.getId(), Map.of("address", request.address())));
        return buildResponse(property);
    }

    @Transactional(readOnly = true)
    public List<PropertyResponse> list(UUID tenantId) {
        return propertyRepository.findByTenant_Id(tenantId).stream()
                .map(this::buildResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PropertyResponse get(UUID tenantId, UUID propertyId) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(Entities.notFound("Property"));
        return buildResponse(property);
    }

    @Transactional
    public PropertyResponse update(UUID tenantId, UUID propertyId, PropertyRequest request) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(Entities.notFound("Property"));

        validateLoanDetails(request);

        property.setAddress(request.address());
        property.setPurchasePrice(request.purchasePrice());
        property.setPurchaseDate(request.purchaseDate());
        property.setCurrentValue(request.currentValue());
        property.setMortgageBalance(request.mortgageBalance() != null
                ? request.mortgageBalance() : BigDecimal.ZERO);
        applyLoanFields(property, request);
        applyPropertyType(property, request.propertyType());
        applyFinancialFields(property, request);
        applyDepreciationFields(property, request);
        property.setUpdatedAt(OffsetDateTime.now());
        property = propertyRepository.save(property);
        log.info("Property {} updated for tenant {}", propertyId, tenantId);
        return buildResponse(property);
    }

    @Transactional
    public void delete(UUID tenantId, UUID propertyId) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(Entities.notFound("Property"));

        var linkedSources = incomeSourceRepository.findByTenant_IdAndProperty_Id(tenantId, propertyId);
        if (!linkedSources.isEmpty()) {
            var names = linkedSources.stream()
                    .map(s -> s.getName())
                    .toList();
            throw new IllegalStateException(
                    "Cannot delete property — it is linked to %d income source(s): %s. Delete these income sources first."
                            .formatted(names.size(), String.join(", ", names)));
        }

        propertyRepository.delete(property);
        log.info("Property {} deleted for tenant {}", propertyId, tenantId);
        eventPublisher.publishEvent(new AuditEvent(tenantId, null, "DELETE", "property",
                propertyId, Map.of()));
    }

    @Transactional(readOnly = true)
    public List<PropertyExpenseResponse> listExpenses(UUID tenantId, UUID propertyId) {
        propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(Entities.notFound("Property"));
        return expenseRepository.findByTenant_IdAndProperty_Id(tenantId, propertyId).stream()
                .map(PropertyExpenseResponse::from)
                .toList();
    }

    @Transactional
    public void deleteExpense(UUID tenantId, UUID propertyId, UUID expenseId) {
        propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(Entities.notFound("Property"));
        var expense = expenseRepository.findByTenant_IdAndId(tenantId, expenseId)
                .filter(e -> e.getProperty() != null && propertyId.equals(e.getProperty().getId()))
                .orElseThrow(Entities.notFound("Expense"));
        expenseRepository.delete(expense);
    }

    @Transactional(readOnly = true)
    public DepreciationScheduleResult getDepreciationSchedule(UUID tenantId, UUID propertyId) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(Entities.notFound("Property", propertyId));

        var method = property.getDepreciationMethod();
        if (method == null || "none".equals(method)) {
            throw new IllegalArgumentException("Depreciation is not configured for this property");
        }

        var landValue = property.getLandValue() != null ? property.getLandValue() : BigDecimal.ZERO;
        var depreciableBasis = property.getPurchasePrice().subtract(landValue);

        if ("cost_segregation".equals(method)) {
            return buildCostSegScheduleResult(property, depreciableBasis);
        }

        var schedule = depreciationCalculator.computeStraightLine(
                property.getPurchasePrice(), landValue,
                property.getInServiceDate(), property.getUsefulLifeYears());

        return buildScheduleResult(method, depreciableBasis, property.getUsefulLifeYears(),
                property.getInServiceDate(), schedule);
    }

    private DepreciationScheduleResult buildScheduleResult(String method, BigDecimal depreciableBasis,
                                                             BigDecimal usefulLifeYears, LocalDate inServiceDate,
                                                             Map<Integer, BigDecimal> schedule) {
        var entries = buildYearEntries(depreciableBasis, schedule);
        return new DepreciationScheduleResult(method, depreciableBasis, usefulLifeYears, inServiceDate, entries);
    }

    private DepreciationScheduleResult buildCostSegScheduleResult(PropertyEntity property, BigDecimal depreciableBasis) {
        var allocations = parseCostSegAllocations(property.getCostSegAllocations());
        var bonusRate = property.getBonusDepreciationRate();
        var schedule = depreciationCalculator.computeCostSegregation(
                allocations, bonusRate, property.getInServiceDate(), property.getCostSegStudyYear());

        var entries = buildYearEntries(depreciableBasis, schedule);
        var classBreakdowns = buildClassBreakdowns(allocations, bonusRate);

        return new DepreciationScheduleResult(
                property.getDepreciationMethod(),
                depreciableBasis,
                property.getUsefulLifeYears(),
                property.getInServiceDate(),
                entries,
                bonusRate,
                allocations,
                classBreakdowns);
    }

    private List<DepreciationScheduleResult.YearEntry> buildYearEntries(BigDecimal depreciableBasis,
                                                                         Map<Integer, BigDecimal> schedule) {
        var cumulative = BigDecimal.ZERO;
        var entries = new ArrayList<DepreciationScheduleResult.YearEntry>();
        for (var entry : schedule.entrySet()) {
            cumulative = cumulative.add(entry.getValue());
            entries.add(new DepreciationScheduleResult.YearEntry(
                    entry.getKey(),
                    entry.getValue(),
                    cumulative,
                    depreciableBasis.subtract(cumulative)));
        }
        return entries;
    }

    private List<DepreciationScheduleResult.ClassBreakdown> buildClassBreakdowns(
            List<CostSegAllocation> allocations, BigDecimal bonusRate) {
        var breakdowns = new ArrayList<DepreciationScheduleResult.ClassBreakdown>();
        for (var alloc : allocations) {
            var lifeYears = switch (alloc.assetClass()) {
                case "5yr" -> new BigDecimal("5");
                case "7yr" -> new BigDecimal("7");
                case "15yr" -> new BigDecimal("15");
                case "27_5yr" -> new BigDecimal("27.5");
                default -> BigDecimal.ZERO;
            };
            boolean isBonusEligible = Set.of("5yr", "7yr", "15yr").contains(alloc.assetClass());
            var bonusAmount = isBonusEligible
                    ? alloc.allocation().multiply(bonusRate).setScale(Money.SCALE, Money.ROUNDING)
                    : BigDecimal.ZERO;
            var remainder = alloc.allocation().subtract(bonusAmount);
            var annualSL = remainder.compareTo(BigDecimal.ZERO) > 0
                    ? remainder.divide(lifeYears, 4, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            int slYears = remainder.compareTo(BigDecimal.ZERO) > 0
                    ? lifeYears.intValue() + 1 // includes partial first/last year
                    : 0;
            breakdowns.add(new DepreciationScheduleResult.ClassBreakdown(
                    alloc.assetClass(), lifeYears, alloc.allocation(),
                    bonusAmount, annualSL, slYears));
        }
        return breakdowns;
    }

    public static List<CostSegAllocation> parseCostSegAllocations(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse cost seg allocations", e);
        }
    }

    @Transactional
    public void addExpense(UUID tenantId, UUID propertyId, PropertyExpenseRequest request) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(Entities.notFound("Property"));

        var frequency = request.frequency() != null ? request.frequency() : "monthly";
        var expense = new PropertyExpenseEntity(property, property.getTenant(),
                request.date(), request.amount(), request.category(), request.description(), frequency);
        expenseRepository.save(expense);
    }

    @Transactional
    public void addIncome(UUID tenantId, UUID propertyId, PropertyIncomeRequest request) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(Entities.notFound("Property"));

        var frequency = request.frequency() != null ? request.frequency() : "monthly";
        var income = new PropertyIncomeEntity(property, property.getTenant(),
                request.date(), request.amount(), request.category(), request.description(), frequency);
        incomeRepository.save(income);
    }

    @Transactional(readOnly = true)
    public List<MonthlyCashFlowEntry> getMonthlyCashFlow(UUID tenantId, UUID propertyId,
                                                          YearMonth from, YearMonth to) {
        return getMonthlyCashFlowDetail(tenantId, propertyId, from, to).stream()
                .map(detail -> new MonthlyCashFlowEntry(
                        detail.month(),
                        detail.totalIncome(),
                        detail.totalExpenses(),
                        detail.netCashFlow()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MonthlyCashFlowDetailEntry> getMonthlyCashFlowDetail(UUID tenantId, UUID propertyId,
                                                                      YearMonth from, YearMonth to) {
        var ctx = loadCashFlowContext(tenantId, propertyId, from, to);
        var property = ctx.property();
        var expenses = ctx.expenses();

        Map<YearMonth, Map<String, BigDecimal>> expenseByCategoryByMonth = new HashMap<>();
        for (var expense : expenses) {
            spreadEntryByCategory(expense.getDate(), expense.getAmount(), expense.getFrequency(),
                    expense.getCategory(), from, to, expenseByCategoryByMonth);
        }

        var derivedExpenses = computeDerivedMonthlyExpenses(property);

        var entries = new ArrayList<MonthlyCashFlowDetailEntry>();
        var current = from;
        var formatter = DateTimeFormatter.ofPattern("yyyy-MM");

        while (!current.isAfter(to)) {
            var categoryMap = new HashMap<>(expenseByCategoryByMonth.getOrDefault(current, Map.of()));
            for (var entry : derivedExpenses.entrySet()) {
                categoryMap.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
            }
            var totalExpenses = categoryMap.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            entries.add(new MonthlyCashFlowDetailEntry(
                    current.format(formatter),
                    BigDecimal.ZERO,
                    new LinkedHashMap<>(categoryMap),
                    totalExpenses,
                    BigDecimal.ZERO.subtract(totalExpenses)
            ));

            current = current.plusMonths(1);
        }

        return entries;
    }

    private record CashFlowContext(PropertyEntity property, List<PropertyExpenseEntity> expenses,
                                   YearMonth from, YearMonth to) {}

    private CashFlowContext loadCashFlowContext(UUID tenantId, UUID propertyId, YearMonth from, YearMonth to) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(Entities.notFound("Property"));

        var fromDate = from.atDay(1);
        var toDate = to.atEndOfMonth();
        var annualFromDate = from.minusMonths(11).atDay(1);

        var allExpenses = expenseRepository.findOverlapping(propertyId, fromDate, toDate, annualFromDate);
        var coveredCategories = entityCoveredCategories(property);
        var expenses = allExpenses.stream()
                .filter(e -> !coveredCategories.contains(e.getCategory()))
                .toList();

        return new CashFlowContext(property, expenses, from, to);
    }

    private void spreadEntryByCategory(LocalDate entryDate, BigDecimal amount, String frequency,
                                        String category, YearMonth rangeFrom, YearMonth rangeTo,
                                        Map<YearMonth, Map<String, BigDecimal>> bucket) {
        if ("annual".equals(frequency)) {
            var monthlyAmount = amount.divide(new BigDecimal("12"), 4, RoundingMode.HALF_UP);
            var entryMonth = YearMonth.from(entryDate);
            for (int i = 0; i < 12; i++) {
                var month = entryMonth.plusMonths(i);
                if (!month.isBefore(rangeFrom) && !month.isAfter(rangeTo)) {
                    bucket.computeIfAbsent(month, k -> new HashMap<>())
                            .merge(category, monthlyAmount, BigDecimal::add);
                }
            }
        } else {
            var month = YearMonth.from(entryDate);
            if (!month.isBefore(rangeFrom) && !month.isAfter(rangeTo)) {
                bucket.computeIfAbsent(month, k -> new HashMap<>())
                        .merge(category, amount, BigDecimal::add);
            }
        }
    }

    private Set<String> entityCoveredCategories(PropertyEntity property) {
        var categories = new java.util.HashSet<String>();
        if (property.getAnnualPropertyTax() != null) {
            categories.add("tax");
        }
        if (property.getAnnualInsuranceCost() != null) {
            categories.add("insurance");
        }
        if (property.getAnnualMaintenanceCost() != null) {
            categories.add("maintenance");
        }
        if (property.hasLoanDetails()) {
            categories.add("mortgage");
        }
        return categories;
    }

    Map<String, BigDecimal> computeDerivedMonthlyExpenses(PropertyEntity property) {
        var result = new HashMap<String, BigDecimal>();
        var twelve = new BigDecimal("12");

        if (property.getAnnualPropertyTax() != null) {
            result.put("tax", property.getAnnualPropertyTax().divide(twelve, 4, RoundingMode.HALF_UP));
        }
        if (property.getAnnualInsuranceCost() != null) {
            result.put("insurance", property.getAnnualInsuranceCost().divide(twelve, 4, RoundingMode.HALF_UP));
        }
        if (property.getAnnualMaintenanceCost() != null) {
            result.put("maintenance", property.getAnnualMaintenanceCost().divide(twelve, 4, RoundingMode.HALF_UP));
        }
        if (property.hasLoanDetails()) {
            var mortgage = AmortizationCalculator.monthlyPayment(
                    property.getLoanAmount(), property.getAnnualInterestRate(), property.getLoanTermMonths());
            if (mortgage != null) {
                result.put("mortgage", mortgage);
            }
        }

        return result;
    }

    BigDecimal computeEffectiveBalance(PropertyEntity property) {
        if (property.isUseComputedBalance() && property.hasLoanDetails()) {
            return AmortizationCalculator.remainingBalance(
                    property.getLoanAmount(),
                    property.getAnnualInterestRate(),
                    property.getLoanTermMonths(),
                    property.getLoanStartDate(),
                    LocalDate.now());
        }
        return property.getMortgageBalance();
    }

    private PropertyResponse buildResponse(PropertyEntity property) {
        var effectiveBalance = computeEffectiveBalance(property);
        return PropertyResponse.from(property, effectiveBalance);
    }

    private void applyLoanFields(PropertyEntity property, PropertyRequest request) {
        property.setLoanAmount(request.loanAmount());
        property.setAnnualInterestRate(request.annualInterestRate());
        property.setLoanTermMonths(request.loanTermMonths());
        property.setLoanStartDate(request.loanStartDate());
        property.setUseComputedBalance(
                request.useComputedBalance() != null && request.useComputedBalance());
    }

    private void applyPropertyType(PropertyEntity property, String propertyType) {
        if (propertyType == null) {
            property.setPropertyType("primary_residence");
        } else if (VALID_PROPERTY_TYPES.contains(propertyType)) {
            property.setPropertyType(propertyType);
        } else {
            throw new IllegalArgumentException(
                    "Invalid property type: " + propertyType + ". Must be one of: " + VALID_PROPERTY_TYPES);
        }
    }

    private void applyFinancialFields(PropertyEntity property, PropertyRequest request) {
        property.setAnnualAppreciationRate(request.annualAppreciationRate());
        property.setAnnualPropertyTax(request.annualPropertyTax());
        property.setAnnualInsuranceCost(request.annualInsuranceCost());
        property.setAnnualMaintenanceCost(request.annualMaintenanceCost());
    }

    private void applyDepreciationFields(PropertyEntity property, PropertyRequest request) {
        var method = request.depreciationMethod();
        if (method == null) {
            method = "none";
        }
        if (!VALID_DEPRECIATION_METHODS.contains(method)) {
            throw new IllegalArgumentException(
                    "Invalid depreciation method: " + method + ". Must be one of: " + VALID_DEPRECIATION_METHODS);
        }
        if (!"none".equals(method) && request.inServiceDate() == null) {
            throw new IllegalArgumentException(
                    "in_service_date is required when depreciation method is " + method);
        }
        property.setDepreciationMethod(method);
        property.setInServiceDate(request.inServiceDate());
        property.setLandValue(request.landValue());
        if (request.usefulLifeYears() != null) {
            property.setUsefulLifeYears(request.usefulLifeYears());
        }

        if ("cost_segregation".equals(method)) {
            applyCostSegFields(property, request);
        } else {
            property.setCostSegAllocations("[]");
            property.setBonusDepreciationRate(BigDecimal.ONE);
            property.setCostSegStudyYear(null);
        }
    }

    private void applyCostSegFields(PropertyEntity property, PropertyRequest request) {
        var allocations = request.costSegAllocations();
        if (allocations == null || allocations.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cost segregation allocations are required when depreciation method is cost_segregation");
        }

        var landValue = request.landValue() != null ? request.landValue() : BigDecimal.ZERO;
        var depreciableBasis = request.purchasePrice().subtract(landValue);

        // Validate asset classes
        for (var alloc : allocations) {
            if (!VALID_ASSET_CLASSES.contains(alloc.assetClass())) {
                throw new IllegalArgumentException(
                        "Invalid asset class: " + alloc.assetClass()
                                + ". Must be one of: " + VALID_ASSET_CLASSES);
            }
            if (alloc.allocation().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException(
                        "Allocation for " + alloc.assetClass() + " must be non-negative");
            }
        }

        // Validate sum equals depreciable basis
        var sum = allocations.stream()
                .map(CostSegAllocation::allocation)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(depreciableBasis) != 0) {
            throw new IllegalArgumentException(
                    "Cost segregation allocations (" + sum
                            + ") must equal depreciable basis (" + depreciableBasis + ")");
        }

        try {
            property.setCostSegAllocations(MAPPER.writeValueAsString(allocations));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize cost seg allocations", e);
        }
        property.setBonusDepreciationRate(
                request.bonusDepreciationRate() != null ? request.bonusDepreciationRate() : BigDecimal.ONE);
        property.setCostSegStudyYear(request.costSegStudyYear());
    }

    private void validateLoanDetails(PropertyRequest request) {
        boolean hasAny = request.loanAmount() != null || request.annualInterestRate() != null
                || request.loanTermMonths() != null || request.loanStartDate() != null;
        boolean hasAll = request.loanAmount() != null && request.annualInterestRate() != null
                && request.loanTermMonths() != null && request.loanStartDate() != null;

        if (hasAny && !hasAll) {
            throw new IllegalArgumentException(
                    "Loan details must be provided in full (loanAmount, annualInterestRate, loanTermMonths, loanStartDate) or not at all");
        }
    }
}
