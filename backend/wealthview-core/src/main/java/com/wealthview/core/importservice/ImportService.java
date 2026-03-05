package com.wealthview.core.importservice;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.importservice.dto.ImportJobResponse;
import com.wealthview.core.transaction.TransactionService;
import com.wealthview.core.transaction.dto.TransactionRequest;
import com.wealthview.core.importservice.dto.CsvParseResult;
import com.wealthview.persistence.entity.ImportJobEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.ImportJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ImportJobRepository importJobRepository;
    private final AccountRepository accountRepository;
    private final TransactionService transactionService;
    private final CsvParser csvParser;

    public ImportService(ImportJobRepository importJobRepository,
                         AccountRepository accountRepository,
                         TransactionService transactionService,
                         CsvParser csvParser) {
        this.importJobRepository = importJobRepository;
        this.accountRepository = accountRepository;
        this.transactionService = transactionService;
        this.csvParser = csvParser;
    }

    @Transactional
    public ImportJobResponse importCsv(UUID tenantId, UUID accountId, InputStream inputStream) throws IOException {
        var parseResult = csvParser.parse(inputStream);
        return processCsvImport(tenantId, accountId, parseResult);
    }

    @Transactional
    public ImportJobResponse processCsvImport(UUID tenantId, UUID accountId, CsvParseResult parseResult) {
        var account = accountRepository.findByTenantIdAndId(tenantId, accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));

        var job = new ImportJobEntity(account.getTenant(), account, "csv");
        job.setStatus("processing");
        job.setTotalRows(parseResult.transactions().size() + parseResult.errors().size());
        job = importJobRepository.save(job);

        int successCount = 0;
        for (var parsed : parseResult.transactions()) {
            try {
                var request = new TransactionRequest(
                        parsed.date(), parsed.type(), parsed.symbol(),
                        parsed.quantity(), parsed.amount());
                transactionService.create(tenantId, accountId, request);
                successCount++;
            } catch (Exception e) {
                log.warn("Failed to import transaction: {}", e.getMessage());
            }
        }

        job.setSuccessfulRows(successCount);
        job.setFailedRows(parseResult.errors().size() + (parseResult.transactions().size() - successCount));
        job.setStatus("completed");
        if (!parseResult.errors().isEmpty()) {
            job.setErrorMessage(parseResult.errors().size() + " rows had parse errors");
        }
        job.setUpdatedAt(OffsetDateTime.now());
        job = importJobRepository.save(job);

        log.info("CSV import completed for account {}: {} successful, {} failed",
                accountId, successCount, job.getFailedRows());
        return ImportJobResponse.from(job);
    }

    @Transactional(readOnly = true)
    public List<ImportJobResponse> listJobs(UUID tenantId) {
        return importJobRepository.findByTenantId(tenantId).stream()
                .map(ImportJobResponse::from)
                .toList();
    }
}
