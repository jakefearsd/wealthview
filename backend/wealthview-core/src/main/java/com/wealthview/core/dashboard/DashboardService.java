package com.wealthview.core.dashboard;

import com.wealthview.core.dashboard.dto.DashboardSummaryResponse;
import com.wealthview.core.dashboard.dto.DashboardSummaryResponse.AccountSummary;
import com.wealthview.core.dashboard.dto.DashboardSummaryResponse.AllocationEntry;
import com.wealthview.core.property.AmortizationCalculator;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
    private final PropertyRepository propertyRepository;

    public DashboardService(AccountRepository accountRepository,
                            HoldingRepository holdingRepository,
                            PriceRepository priceRepository,
                            TransactionRepository transactionRepository,
                            PropertyRepository propertyRepository) {
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.priceRepository = priceRepository;
        this.transactionRepository = transactionRepository;
        this.propertyRepository = propertyRepository;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse getSummary(UUID tenantId) {
        var accounts = accountRepository.findByTenant_Id(tenantId, Pageable.unpaged());
        var holdings = holdingRepository.findByTenant_Id(tenantId);

        var totalInvestments = BigDecimal.ZERO;
        var totalCash = BigDecimal.ZERO;
        var accountSummaries = new ArrayList<AccountSummary>();
        var allocationMap = new HashMap<String, BigDecimal>();

        for (var account : accounts) {
            var accountBalance = "bank".equals(account.getType())
                    ? computeBankBalance(account, tenantId)
                    : computeInvestmentValue(account, holdings);

            if ("bank".equals(account.getType())) {
                totalCash = totalCash.add(accountBalance);
            } else {
                totalInvestments = totalInvestments.add(accountBalance);
            }

            accountSummaries.add(new AccountSummary(
                    account.getName(), account.getType(), accountBalance));
            allocationMap.merge(account.getType(), accountBalance, BigDecimal::add);
        }

        var totalPropertyEquity = computePropertySummaries(tenantId, accountSummaries, allocationMap);

        var netWorth = totalInvestments.add(totalCash).add(totalPropertyEquity);
        var allocation = buildAllocation(allocationMap, netWorth);

        return new DashboardSummaryResponse(
                netWorth, totalInvestments, totalCash,
                totalPropertyEquity, accountSummaries, allocation);
    }

    private BigDecimal computeBankBalance(AccountEntity account, UUID tenantId) {
        var transactions = transactionRepository.findByAccount_IdAndTenant_Id(
                account.getId(), tenantId, Pageable.unpaged());
        var balance = BigDecimal.ZERO;
        for (var txn : transactions) {
            if ("deposit".equals(txn.getType())) {
                balance = balance.add(txn.getAmount());
            } else if ("withdrawal".equals(txn.getType())) {
                balance = balance.subtract(txn.getAmount());
            }
        }
        return balance;
    }

    private BigDecimal computeInvestmentValue(AccountEntity account, List<HoldingEntity> holdings) {
        var value = BigDecimal.ZERO;
        var accountHoldings = holdings.stream()
                .filter(h -> account.getId().equals(h.getAccountId()))
                .toList();
        for (var holding : accountHoldings) {
            value = value.add(getHoldingValue(holding));
        }
        return value;
    }

    private BigDecimal computePropertySummaries(UUID tenantId,
                                                 List<AccountSummary> accountSummaries,
                                                 HashMap<String, BigDecimal> allocationMap) {
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

    private BigDecimal getHoldingValue(HoldingEntity holding) {
        return priceRepository.findFirstBySymbolOrderByDateDesc(holding.getSymbol())
                .map(price -> holding.getQuantity().multiply(price.getClosePrice())
                        .setScale(4, RoundingMode.HALF_UP))
                .orElse(holding.getCostBasis());
    }

    private BigDecimal computeEffectiveBalance(PropertyEntity property) {
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
