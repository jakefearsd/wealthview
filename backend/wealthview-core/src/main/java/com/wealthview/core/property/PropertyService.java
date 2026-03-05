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
import java.util.UUID;

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

        var mortgageBalance = request.mortgageBalance() != null
                ? request.mortgageBalance() : BigDecimal.ZERO;

        var property = new PropertyEntity(tenant, request.address(), request.purchasePrice(),
                request.purchaseDate(), request.currentValue(), mortgageBalance);
        property = propertyRepository.save(property);
        log.info("Property {} created for tenant {}", property.getId(), tenantId);
        return PropertyResponse.from(property);
    }

    @Transactional(readOnly = true)
    public List<PropertyResponse> list(UUID tenantId) {
        return propertyRepository.findByTenantId(tenantId).stream()
                .map(PropertyResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PropertyResponse get(UUID tenantId, UUID propertyId) {
        var property = propertyRepository.findByTenantIdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        return PropertyResponse.from(property);
    }

    @Transactional
    public PropertyResponse update(UUID tenantId, UUID propertyId, PropertyRequest request) {
        var property = propertyRepository.findByTenantIdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        property.setAddress(request.address());
        property.setPurchasePrice(request.purchasePrice());
        property.setPurchaseDate(request.purchaseDate());
        property.setCurrentValue(request.currentValue());
        property.setMortgageBalance(request.mortgageBalance() != null
                ? request.mortgageBalance() : BigDecimal.ZERO);
        property.setUpdatedAt(OffsetDateTime.now());
        property = propertyRepository.save(property);
        return PropertyResponse.from(property);
    }

    @Transactional
    public void delete(UUID tenantId, UUID propertyId) {
        var property = propertyRepository.findByTenantIdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        propertyRepository.delete(property);
        log.info("Property {} deleted for tenant {}", propertyId, tenantId);
    }

    @Transactional
    public void addIncome(UUID tenantId, UUID propertyId, PropertyIncomeRequest request) {
        var property = propertyRepository.findByTenantIdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        var income = new PropertyIncomeEntity(property, property.getTenant(),
                request.date(), request.amount(), request.category(), request.description());
        incomeRepository.save(income);
    }

    @Transactional
    public void addExpense(UUID tenantId, UUID propertyId, PropertyExpenseRequest request) {
        var property = propertyRepository.findByTenantIdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        var expense = new PropertyExpenseEntity(property, property.getTenant(),
                request.date(), request.amount(), request.category(), request.description());
        expenseRepository.save(expense);
    }

    @Transactional(readOnly = true)
    public List<MonthlyCashFlowEntry> getMonthlyCashFlow(UUID tenantId, UUID propertyId,
                                                          YearMonth from, YearMonth to) {
        propertyRepository.findByTenantIdAndId(tenantId, propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));

        var fromDate = from.atDay(1);
        var toDate = to.atEndOfMonth();

        var incomes = incomeRepository.findByPropertyIdAndDateBetween(propertyId, fromDate, toDate);
        var expenses = expenseRepository.findByPropertyIdAndDateBetween(propertyId, fromDate, toDate);

        var entries = new ArrayList<MonthlyCashFlowEntry>();
        var current = from;
        var formatter = DateTimeFormatter.ofPattern("yyyy-MM");

        while (!current.isAfter(to)) {
            var month = current;
            var monthIncome = incomes.stream()
                    .filter(i -> YearMonth.from(i.getDate()).equals(month))
                    .map(PropertyIncomeEntity::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            var monthExpense = expenses.stream()
                    .filter(e -> YearMonth.from(e.getDate()).equals(month))
                    .map(PropertyExpenseEntity::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            entries.add(new MonthlyCashFlowEntry(
                    month.format(formatter),
                    monthIncome,
                    monthExpense,
                    monthIncome.subtract(monthExpense)
            ));

            current = current.plusMonths(1);
        }

        return entries;
    }
}
