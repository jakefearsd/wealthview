package com.wealthview.core.dashboard;

import com.wealthview.core.dashboard.dto.DashboardSummaryResponse;
import com.wealthview.core.dashboard.dto.DashboardSummaryResponse.AccountSummary;
import com.wealthview.core.dashboard.dto.DashboardSummaryResponse.AllocationEntry;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
public class DashboardService {

    private final AccountRepository accountRepository;
    private final HoldingRepository holdingRepository;
    private final PriceRepository priceRepository;
    private final TransactionRepository transactionRepository;

    public DashboardService(AccountRepository accountRepository,
                            HoldingRepository holdingRepository,
                            PriceRepository priceRepository,
                            TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.priceRepository = priceRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse getSummary(UUID tenantId) {
        var accounts = accountRepository.findByTenantId(tenantId, Pageable.unpaged());
        var holdings = holdingRepository.findByTenantId(tenantId);

        var totalInvestments = BigDecimal.ZERO;
        var totalCash = BigDecimal.ZERO;
        var accountSummaries = new ArrayList<AccountSummary>();
        var allocationMap = new HashMap<String, BigDecimal>();

        for (var account : accounts) {
            var accountBalance = BigDecimal.ZERO;

            if ("bank".equals(account.getType())) {
                var transactions = transactionRepository.findByAccountIdAndTenantId(
                        account.getId(), tenantId, Pageable.unpaged());
                for (var txn : transactions) {
                    if ("deposit".equals(txn.getType())) {
                        accountBalance = accountBalance.add(txn.getAmount());
                    } else if ("withdrawal".equals(txn.getType())) {
                        accountBalance = accountBalance.subtract(txn.getAmount());
                    }
                }
                totalCash = totalCash.add(accountBalance);
            } else {
                var accountHoldings = holdings.stream()
                        .filter(h -> account.getId().equals(h.getAccountId()))
                        .toList();
                for (var holding : accountHoldings) {
                    accountBalance = accountBalance.add(getHoldingValue(holding));
                }
                totalInvestments = totalInvestments.add(accountBalance);
            }

            accountSummaries.add(new AccountSummary(
                    account.getName(), account.getType(), accountBalance));

            allocationMap.merge(account.getType(), accountBalance, BigDecimal::add);
        }

        var netWorth = totalInvestments.add(totalCash);

        var allocation = buildAllocation(allocationMap, netWorth);

        return new DashboardSummaryResponse(
                netWorth, totalInvestments, totalCash,
                BigDecimal.ZERO, accountSummaries, allocation);
    }

    private BigDecimal getHoldingValue(HoldingEntity holding) {
        return priceRepository.findFirstBySymbolOrderByDateDesc(holding.getSymbol())
                .map(price -> holding.getQuantity().multiply(price.getClosePrice())
                        .setScale(4, RoundingMode.HALF_UP))
                .orElse(holding.getCostBasis());
    }

    private List<AllocationEntry> buildAllocation(HashMap<String, BigDecimal> map, BigDecimal total) {
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
