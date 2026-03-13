package com.wealthview.core.importservice;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.importservice.dto.ImportJobResponse;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@Service
public class PositionImportService {

    private static final Logger log = LoggerFactory.getLogger(PositionImportService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final HoldingRepository holdingRepository;
    private final EntityManager entityManager;
    private final ImportService importService;

    public PositionImportService(AccountRepository accountRepository,
                                 TransactionRepository transactionRepository,
                                 HoldingRepository holdingRepository,
                                 EntityManager entityManager,
                                 ImportService importService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.holdingRepository = holdingRepository;
        this.entityManager = entityManager;
        this.importService = importService;
    }

    @Transactional
    public ImportJobResponse importPositions(UUID tenantId, UUID accountId,
                                              InputStream inputStream, String format) throws IOException {
        var parser = importService.resolveParser(format);
        var parseResult = parser.parse(inputStream);

        accountRepository.findByTenant_IdAndId(tenantId, accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));

        transactionRepository.deleteByAccount_IdAndTenant_Id(accountId, tenantId);
        holdingRepository.deleteByAccount_IdAndTenant_Id(accountId, tenantId);
        entityManager.flush();

        var result = importService.processImport(tenantId, accountId, parseResult, "positions");

        log.info("Positions import completed for account {}: {} successful, {} failed",
                accountId, result.successfulRows(), result.failedRows());
        return result;
    }
}
