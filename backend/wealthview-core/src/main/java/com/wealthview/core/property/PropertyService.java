package com.wealthview.core.property;

import com.wealthview.core.audit.AuditEvent;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.property.dto.MonthlyCashFlowDetailEntry;
import com.wealthview.core.property.dto.MonthlyCashFlowEntry;
import com.wealthview.core.property.dto.PropertyExpenseRequest;
import com.wealthview.core.property.dto.PropertyIncomeRequest;
import com.wealthview.core.property.dto.PropertyRequest;
import com.wealthview.core.property.dto.PropertyResponse;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.PropertyExpenseEntity;
import com.wealthview.persistence.entity.PropertyIncomeEntity;
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

@Service
public class PropertyService {

    private static final Logger log = LoggerFactory.getLogger(PropertyService.class);
    private static final Set<String> VALID_PROPERTY_TYPES = Set.of("primary_residence", "investment", "vacation");
    private static final Set<String> VALID_DEPRECIATION_METHODS = Set.of("none", "straight_line", "cost_segregation");

    private final PropertyRepository propertyRepository;
    private final PropertyIncomeRepository incomeRepository;
    private final PropertyExpenseRepository expenseRepository;
    private final TenantRepository tenantRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PropertyService(PropertyRepository propertyRepository,
                           PropertyIncomeRepository incomeRepository,
                           PropertyExpenseRepository expenseRepository,
                           TenantRepository tenantRepository,
                           ApplicationEventPublisher eventPublisher) {
        this.propertyRepository = propertyRepository;
        this.incomeRepository = incomeRepository;
        this.expenseRepository = expenseRepository;
        this.tenantRepository = tenantRepository;
        this.eventPublisher = eventPublisher;
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
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        return buildResponse(property);
    }

    @Transactional
    public PropertyResponse update(UUID tenantId, UUID propertyId, PropertyRequest request) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

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
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        propertyRepository.delete(property);
        log.info("Property {} deleted for tenant {}", propertyId, tenantId);
        eventPublisher.publishEvent(new AuditEvent(tenantId, null, "DELETE", "property",
                propertyId, Map.of()));
    }

    @Transactional
    public void addIncome(UUID tenantId, UUID propertyId, PropertyIncomeRequest request) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        var frequency = request.frequency() != null ? request.frequency() : "monthly";
        var income = new PropertyIncomeEntity(property, property.getTenant(),
                request.date(), request.amount(), request.category(), request.description(), frequency);
        incomeRepository.save(income);
    }

    @Transactional
    public void addExpense(UUID tenantId, UUID propertyId, PropertyExpenseRequest request) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        var frequency = request.frequency() != null ? request.frequency() : "monthly";
        var expense = new PropertyExpenseEntity(property, property.getTenant(),
                request.date(), request.amount(), request.category(), request.description(), frequency);
        expenseRepository.save(expense);
    }

    @Transactional(readOnly = true)
    public List<MonthlyCashFlowEntry> getMonthlyCashFlow(UUID tenantId, UUID propertyId,
                                                          YearMonth from, YearMonth to) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        var fromDate = from.atDay(1);
        var toDate = to.atEndOfMonth();
        var annualFromDate = from.minusMonths(11).atDay(1);

        var incomes = incomeRepository.findOverlapping(propertyId, fromDate, toDate, annualFromDate);
        var expenses = expenseRepository.findOverlapping(propertyId, fromDate, toDate, annualFromDate);

        Map<YearMonth, BigDecimal> incomeByMonth = new HashMap<>();
        for (var income : incomes) {
            spreadEntry(income.getDate(), income.getAmount(), income.getFrequency(),
                    from, to, incomeByMonth);
        }

        Map<YearMonth, BigDecimal> expenseByMonth = new HashMap<>();
        for (var expense : expenses) {
            spreadEntry(expense.getDate(), expense.getAmount(), expense.getFrequency(),
                    from, to, expenseByMonth);
        }

        var derivedTotal = computeDerivedMonthlyExpenses(property).values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        var entries = new ArrayList<MonthlyCashFlowEntry>();
        var current = from;
        var formatter = DateTimeFormatter.ofPattern("yyyy-MM");

        while (!current.isAfter(to)) {
            var monthIncome = incomeByMonth.getOrDefault(current, BigDecimal.ZERO);
            var monthExpense = expenseByMonth.getOrDefault(current, BigDecimal.ZERO).add(derivedTotal);

            entries.add(new MonthlyCashFlowEntry(
                    current.format(formatter),
                    monthIncome,
                    monthExpense,
                    monthIncome.subtract(monthExpense)
            ));

            current = current.plusMonths(1);
        }

        return entries;
    }

    @Transactional(readOnly = true)
    public List<MonthlyCashFlowDetailEntry> getMonthlyCashFlowDetail(UUID tenantId, UUID propertyId,
                                                                      YearMonth from, YearMonth to) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        var fromDate = from.atDay(1);
        var toDate = to.atEndOfMonth();
        var annualFromDate = from.minusMonths(11).atDay(1);

        var incomes = incomeRepository.findOverlapping(propertyId, fromDate, toDate, annualFromDate);
        var expenses = expenseRepository.findOverlapping(propertyId, fromDate, toDate, annualFromDate);

        Map<YearMonth, BigDecimal> incomeByMonth = new HashMap<>();
        for (var income : incomes) {
            spreadEntry(income.getDate(), income.getAmount(), income.getFrequency(),
                    from, to, incomeByMonth);
        }

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
            var monthIncome = incomeByMonth.getOrDefault(current, BigDecimal.ZERO);
            var categoryMap = new HashMap<>(expenseByCategoryByMonth.getOrDefault(current, Map.of()));
            for (var entry : derivedExpenses.entrySet()) {
                categoryMap.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
            }
            var totalExpenses = categoryMap.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            entries.add(new MonthlyCashFlowDetailEntry(
                    current.format(formatter),
                    monthIncome,
                    new LinkedHashMap<>(categoryMap),
                    totalExpenses,
                    monthIncome.subtract(totalExpenses)
            ));

            current = current.plusMonths(1);
        }

        return entries;
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

    private void spreadEntry(LocalDate entryDate, BigDecimal amount, String frequency,
                              YearMonth rangeFrom, YearMonth rangeTo,
                              Map<YearMonth, BigDecimal> bucket) {
        if ("annual".equals(frequency)) {
            var monthlyAmount = amount.divide(new BigDecimal("12"), 4, RoundingMode.HALF_UP);
            var entryMonth = YearMonth.from(entryDate);
            for (int i = 0; i < 12; i++) {
                var month = entryMonth.plusMonths(i);
                if (!month.isBefore(rangeFrom) && !month.isAfter(rangeTo)) {
                    bucket.merge(month, monthlyAmount, BigDecimal::add);
                }
            }
        } else {
            var month = YearMonth.from(entryDate);
            if (!month.isBefore(rangeFrom) && !month.isAfter(rangeTo)) {
                bucket.merge(month, amount, BigDecimal::add);
            }
        }
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
        property.setDepreciationMethod(method);
        property.setInServiceDate(request.inServiceDate());
        property.setLandValue(request.landValue());
        if (request.usefulLifeYears() != null) {
            property.setUsefulLifeYears(request.usefulLifeYears());
        }
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
