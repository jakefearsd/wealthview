package com.wealthview.core.property;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.wealthview.core.property.dto.MonthlyCashFlowDetailEntry;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.PropertyExpenseEntity;

/**
 * Builds monthly cash-flow projections for a property.
 *
 * <p>Extracted from {@code PropertyService} as the cash-flow analysis collaborator. It
 * performs no data access: {@code PropertyService} keeps the {@code @Transactional}
 * read boundary, performs the tenant-scoped {@code findByTenant_IdAndId} lookup, fetches
 * the overlapping expenses, and hands the resolved entity plus its expenses to this
 * collaborator for pure arithmetic spreading.
 */
@Service
class PropertyCashFlowService {

    /**
     * Computes the per-category monthly cash-flow detail for a property over the given
     * inclusive {@code from}/{@code to} window. All inputs are pre-loaded by the caller;
     * {@code expenses} must already exclude any category covered by an entity-level
     * derived expense (tax/insurance/maintenance/mortgage).
     */
    List<MonthlyCashFlowDetailEntry> buildMonthlyCashFlowDetail(PropertyEntity property,
                                                                 List<PropertyExpenseEntity> expenses,
                                                                 YearMonth from, YearMonth to) {
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

    /**
     * Returns the set of expense categories that are covered by entity-level derived
     * expenses, so the caller can exclude duplicate ad-hoc expense rows.
     */
    Set<String> entityCoveredCategories(PropertyEntity property) {
        var categories = new HashSet<String>();
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
}
