package com.wealthview.core.config;

import com.wealthview.core.config.dto.SystemStatsResponse;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import com.wealthview.persistence.repository.UserRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemStatsService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final AccountRepository accountRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;
    private final PriceRepository priceRepository;

    public SystemStatsService(UserRepository userRepository,
                              TenantRepository tenantRepository,
                              AccountRepository accountRepository,
                              HoldingRepository holdingRepository,
                              TransactionRepository transactionRepository,
                              PriceRepository priceRepository) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.priceRepository = priceRepository;
    }

    @Transactional(readOnly = true)
    public SystemStatsResponse getStats() {
        long totalUsers = userRepository.count();
        long activeUsers = userRepository.findAll().stream()
                .filter(u -> u.isActive())
                .count();
        long totalTenants = tenantRepository.count();
        long totalAccounts = accountRepository.count();
        long totalHoldings = holdingRepository.count();
        long totalTransactions = transactionRepository.count();
        long symbolsTracked = priceRepository.findLatestPerSymbol().size();
        long staleSymbols = 0L;

        return new SystemStatsResponse(
                totalUsers,
                activeUsers,
                totalTenants,
                totalAccounts,
                totalHoldings,
                totalTransactions,
                "N/A",
                symbolsTracked,
                staleSymbols
        );
    }
}
