package com.wealthview.core.property;

import com.wealthview.core.exception.EntityNotFoundException;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PropertyService {

    private static final Logger log = LoggerFactory.getLogger(PropertyService.class);

    private final PropertyRepository propertyRepository;
    private final PropertyIncomeRepository incomeRepository;
    private final PropertyExpenseRepository expenseRepository;
    private final TenantRepository tenantRepository;

    public PropertyService(PropertyRepository propertyRepository,
                           PropertyIncomeRepository incomeRepository,
                           PropertyExpenseRepository expenseRepository,
                           TenantRepository tenantRepository) {
        this.propertyRepository = propertyRepository;
        this.incomeRepository = incomeRepository;
        this.expenseRepository = expenseRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public PropertyResponse create(UUID tenantId, PropertyRequest request) {
        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Tenant not found"));

        validateLoanDetails(request);

        var mortgageBalance = request.mortgageBalance() != null
                ? request.mortgageBalance() : BigDecimal.ZERO;

        var property = new PropertyEntity(tenant, request.address(), request.purchasePrice(),
                request.purchaseDate(), request.currentValue(), mortgageBalance);
        applyLoanFields(property, request);
        property = propertyRepository.save(property);
        log.info("Property {} created for tenant {}", property.getId(), tenantId);
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
        property.setUpdatedAt(OffsetDateTime.now());
        property = propertyRepository.save(property);
        return buildResponse(property);
    }

    @Transactional
    public void delete(UUID tenantId, UUID propertyId) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        propertyRepository.delete(property);
        log.info("Property {} deleted for tenant {}", propertyId, tenantId);
    }

    @Transactional
    public void addIncome(UUID tenantId, UUID propertyId, PropertyIncomeRequest request) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        var income = new PropertyIncomeEntity(property, property.getTenant(),
                request.date(), request.amount(), request.category(), request.description());
        incomeRepository.save(income);
    }

    @Transactional
    public void addExpense(UUID tenantId, UUID propertyId, PropertyExpenseRequest request) {
        var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        var expense = new PropertyExpenseEntity(property, property.getTenant(),
                request.date(), request.amount(), request.category(), request.description());
        expenseRepository.save(expense);
    }

    // TODO: depreciation as non-cash expense could be factored in here
    @Transactional(readOnly = true)
    public List<MonthlyCashFlowEntry> getMonthlyCashFlow(UUID tenantId, UUID propertyId,
                                                          YearMonth from, YearMonth to) {
        propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        var fromDate = from.atDay(1);
        var toDate = to.atEndOfMonth();

        var incomes = incomeRepository.findByProperty_IdAndDateBetween(propertyId, fromDate, toDate);
        var expenses = expenseRepository.findByProperty_IdAndDateBetween(propertyId, fromDate, toDate);

        Map<YearMonth, BigDecimal> incomeByMonth = incomes.stream()
                .collect(Collectors.groupingBy(
                        i -> YearMonth.from(i.getDate()),
                        Collectors.reducing(BigDecimal.ZERO, PropertyIncomeEntity::getAmount, BigDecimal::add)));

        Map<YearMonth, BigDecimal> expenseByMonth = expenses.stream()
                .collect(Collectors.groupingBy(
                        e -> YearMonth.from(e.getDate()),
                        Collectors.reducing(BigDecimal.ZERO, PropertyExpenseEntity::getAmount, BigDecimal::add)));

        var entries = new ArrayList<MonthlyCashFlowEntry>();
        var current = from;
        var formatter = DateTimeFormatter.ofPattern("yyyy-MM");

        while (!current.isAfter(to)) {
            var monthIncome = incomeByMonth.getOrDefault(current, BigDecimal.ZERO);
            var monthExpense = expenseByMonth.getOrDefault(current, BigDecimal.ZERO);

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
