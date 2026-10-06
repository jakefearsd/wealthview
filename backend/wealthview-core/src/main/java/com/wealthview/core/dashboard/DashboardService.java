package com.wealthview.core.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.account.AccountService;
import com.wealthview.core.dashboard.dto.DashboardSummaryResponse;
import com.wealthview.core.dashboard.dto.DashboardSummaryResponse.AccountSummary;
import com.wealthview.core.dashboard.dto.DashboardSummaryResponse.AllocationEntry;
import com.wealthview.core.exchangerate.ExchangeRateService;
import com.wealthview.core.property.PropertyFinance;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import io.micrometer.core.annotation.Timed;

@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final ExchangeRateService exchangeRateService;
    private final PropertyRepository propertyRepository;

    public DashboardService(AccountRepository accountRepository,
                            AccountService accountService,
                            ExchangeRateService exchangeRateService,
                            PropertyRepository propertyRepository) {
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.exchangeRateService = exchangeRateService;
        this.propertyRepository = propertyRepository;
    }

    @Timed("wealthview.dashboard.summary")
    @Transactional(readOnly = true)
    public DashboardSummaryResponse getSummary(UUID tenantId) {
        log.debug("Computing dashboard summary for tenant {}", tenantId);
        var accounts = accountRepository.findByTenant_Id(tenantId, Pageable.unpaged());

        var totalInvestments = BigDecimal.ZERO;
        var totalCash = BigDecimal.ZERO;
        var accountSummaries = new ArrayList<AccountSummary>();
        var unconvertedAccounts = new ArrayList<String>();
        var allocationMap = new HashMap<String, BigDecimal>();

        var balances = accountService.computeAllBalances(tenantId);

        for (var account : accounts) {
            if (!exchangeRateService.hasRate(tenantId, account.getCurrency())) {
                // No exchange rate for this account's currency: leave it out of the totals (adding native
                // units to dollars would be wrong) and flag it, rather than failing the whole dashboard.
                // Checked up front rather than by catching convertToUsd's EntityNotFoundException: that is
                // thrown through the resolver's @Transactional proxy, which marks this read-only
                // transaction rollback-only, so the summary would still fail on commit.
                log.warn("Dashboard for tenant {} skips account {}: no exchange rate for {}",
                        tenantId, account.getId(), account.getCurrency());
                unconvertedAccounts.add(account.getName());
                continue;
            }
            var nativeBalance = balances.getOrDefault(account.getId(), BigDecimal.ZERO);
            var accountBalanceUsd = exchangeRateService.convertToUsd(nativeBalance, account.getCurrency(), tenantId);

            if (account.isBank()) {
                totalCash = totalCash.add(accountBalanceUsd);
            } else {
                totalInvestments = totalInvestments.add(accountBalanceUsd);
            }

            accountSummaries.add(new AccountSummary(
                    account.getName(), account.getType(), accountBalanceUsd));
            allocationMap.merge(account.getType(), accountBalanceUsd, BigDecimal::add);
        }

        var totalPropertyEquity = computePropertySummaries(tenantId, accountSummaries, allocationMap);

        var netWorth = totalInvestments.add(totalCash).add(totalPropertyEquity);
        var allocation = buildAllocation(allocationMap, netWorth);

        log.info("Dashboard summary for tenant {}: net worth {}", tenantId, netWorth);
        return new DashboardSummaryResponse(
                netWorth, totalInvestments, totalCash,
                totalPropertyEquity, accountSummaries, allocation, unconvertedAccounts);
    }

    private BigDecimal computePropertySummaries(UUID tenantId,
                                                 List<AccountSummary> accountSummaries,
                                                 Map<String, BigDecimal> allocationMap) {
        var totalPropertyEquity = BigDecimal.ZERO;
        var properties = propertyRepository.findByTenant_Id(tenantId);
        for (var property : properties) {
            var effectiveBalance = computeEffectiveBalance(property);
            var equity = property.getCurrentValue().subtract(effectiveBalance);
            totalPropertyEquity = totalPropertyEquity.add(equity);
            accountSummaries.add(new AccountSummary(
                    property.getAddress(), "property", equity));
            allocationMap.merge("property", equity, BigDecimal::add);
        }
        return totalPropertyEquity;
    }

    private BigDecimal computeEffectiveBalance(PropertyEntity property) {
        return PropertyFinance.effectiveCurrentMortgageBalance(property);
    }

    private List<AllocationEntry> buildAllocation(Map<String, BigDecimal> map, BigDecimal total) {
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            return map.entrySet().stream()
                    .map(e -> new AllocationEntry(e.getKey(), e.getValue(), BigDecimal.ZERO))
                    .toList();
        }
        return map.entrySet().stream()
                .map(e -> new AllocationEntry(
                        e.getKey(),
                        e.getValue(),
                        e.getValue().divide(total, 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"))
                                .setScale(2, RoundingMode.HALF_UP)))
                .toList();
    }
}
