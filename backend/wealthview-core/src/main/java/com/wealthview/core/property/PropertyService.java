package com.wealthview.core.property;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.audit.AuditEvent;
import com.wealthview.core.common.Entities;
import com.wealthview.core.property.dto.CostSegAllocation;
import com.wealthview.core.property.dto.DepreciationScheduleResult;
import com.wealthview.core.property.dto.MonthlyCashFlowDetailEntry;
import com.wealthview.core.property.dto.MonthlyCashFlowEntry;
import com.wealthview.core.property.dto.PropertyExpenseRequest;
import com.wealthview.core.property.dto.PropertyExpenseResponse;
import com.wealthview.core.property.dto.PropertyIncomeRequest;
import com.wealthview.core.property.dto.PropertyRequest;
import com.wealthview.core.property.dto.PropertyResponse;
import com.wealthview.core.tenant.TenantLookup;
import com.wealthview.persistence.entity.IncomeSourceEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.PropertyExpenseEntity;
import com.wealthview.persistence.entity.PropertyIncomeEntity;
import com.wealthview.persistence.repository.IncomeSourceRepository;
import com.wealthview.persistence.repository.PropertyExpenseRepository;
import com.wealthview.persistence.repository.PropertyIncomeRepository;
import com.wealthview.persistence.repository.PropertyRepository;

@Service
public class PropertyService {

    private static final Logger log = LoggerFactory.getLogger(PropertyService.class);
    private static final Set<String> VALID_PROPERTY_TYPES = Set.of("primary_residence", "investment", "vacation");

    private final PropertyRepository propertyRepository;
    private final PropertyExpenseRepository expenseRepository;
    private final PropertyIncomeRepository incomeRepository;
    private final IncomeSourceRepository incomeSourceRepository;
    private final TenantLookup tenantLookup;
    private final ApplicationEventPublisher eventPublisher;
    private final PropertyDepreciationService depreciationService;
    private final PropertyCashFlowService cashFlowService;

    public PropertyService(PropertyRepository propertyRepository,
                           PropertyExpenseRepository expenseRepository,
                           PropertyIncomeRepository incomeRepository,
                           IncomeSourceRepository incomeSourceRepository,
                           TenantLookup tenantLookup,
                           ApplicationEventPublisher eventPublisher,
                           PropertyDepreciationService depreciationService,
                           PropertyCashFlowService cashFlowService) {
        this.propertyRepository = propertyRepository;
        this.expenseRepository = expenseRepository;
        this.incomeRepository = incomeRepository;
        this.incomeSourceRepository = incomeSourceRepository;
        this.tenantLookup = tenantLookup;
        this.eventPublisher = eventPublisher;
        this.depreciationService = depreciationService;
        this.cashFlowService = cashFlowService;
    }

    @Transactional
    public PropertyResponse create(UUID tenantId, PropertyRequest request) {
        var tenant = tenantLookup.requireTenant(tenantId);

        validateLoanDetails(request);

        var mortgageBalance = request.mortgageBalance() != null
                ? request.mortgageBalance() : BigDecimal.ZERO;

        var property = new PropertyEntity(tenant, request.address(), request.purchasePrice(),
                request.purchaseDate(), request.currentValue(), mortgageBalance);
        applyLoanFields(property, request);
        applyPropertyType(property, request.propertyType());
        applyFinancialFields(property, request);
        depreciationService.applyDepreciationFields(property, request);
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
        depreciationService.applyDepreciationFields(property, request);
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
                    .map(IncomeSourceEntity::getName)
                    .toList();
            throw new IllegalStateException((
                    "Cannot delete property — it is linked to %d income source(s): %s."
                    + " Delete these income sources first.")
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
        return depreciationService.buildSchedule(property);
    }

    public static List<CostSegAllocation> parseCostSegAllocations(String json) {
        return PropertyDepreciationService.parseCostSegAllocations(json);
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
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(Entities.notFound("Property"));

        var fromDate = from.atDay(1);
        var toDate = to.atEndOfMonth();
        var annualFromDate = from.minusMonths(11).atDay(1);

        var allExpenses = expenseRepository.findOverlapping(propertyId, fromDate, toDate, annualFromDate);
        var coveredCategories = cashFlowService.entityCoveredCategories(property);
        var expenses = allExpenses.stream()
                .filter(e -> !coveredCategories.contains(e.getCategory()))
                .toList();

        return cashFlowService.buildMonthlyCashFlowDetail(property, expenses, from, to);
    }

    BigDecimal computeEffectiveBalance(PropertyEntity property) {
        return PropertyFinance.effectiveCurrentMortgageBalance(property);
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

    private void validateLoanDetails(PropertyRequest request) {
        boolean hasAny = request.loanAmount() != null || request.annualInterestRate() != null
                || request.loanTermMonths() != null || request.loanStartDate() != null;
        boolean hasAll = request.loanAmount() != null && request.annualInterestRate() != null
                && request.loanTermMonths() != null && request.loanStartDate() != null;

        if (hasAny && !hasAll) {
            throw new IllegalArgumentException(
                    "Loan details must be provided in full"
                    + " (loanAmount, annualInterestRate, loanTermMonths, loanStartDate) or not at all");
        }
    }
}
