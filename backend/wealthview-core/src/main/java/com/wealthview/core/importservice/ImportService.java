package com.wealthview.core.importservice;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.importservice.dto.ImportJobResponse;
import com.wealthview.core.transaction.TransactionService;
import com.wealthview.core.transaction.dto.TransactionRequest;
import com.wealthview.core.importservice.dto.CsvParseResult;
import com.wealthview.persistence.entity.ImportJobEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.ImportJobRepository;
import com.wealthview.persistence.repository.TransactionRepository;
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
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final CsvParser csvParser;
    private final java.util.Map<String, CsvParser> namedParsers;

    public ImportService(ImportJobRepository importJobRepository,
                         AccountRepository accountRepository,
                         TransactionRepository transactionRepository,
                         TransactionService transactionService,
                         CsvParser csvParser,
                         java.util.Map<String, CsvParser> namedParsers) {
        this.importJobRepository = importJobRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.transactionService = transactionService;
        this.csvParser = csvParser;
        this.namedParsers = namedParsers;
    }

    @Transactional
    public ImportJobResponse importCsv(UUID tenantId, UUID accountId, InputStream inputStream) throws IOException {
        var parseResult = csvParser.parse(inputStream);
        return processCsvImport(tenantId, accountId, parseResult);
    }

    @Transactional
    public ImportJobResponse importCsv(UUID tenantId, UUID accountId, InputStream inputStream, String format) throws IOException {
        var parser = resolveParser(format);
        var parseResult = parser.parse(inputStream);
        return processCsvImport(tenantId, accountId, parseResult);
    }

    private CsvParser resolveParser(String format) {
        if (format == null || format.isBlank() || "generic".equals(format)) {
            return csvParser;
        }
        var parserName = format + "CsvParser";
        var parser = namedParsers.get(parserName);
        if (parser == null) {
            throw new IllegalArgumentException("Unknown CSV format: " + format);
        }
        return parser;
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
        int skippedDuplicates = 0;
        for (var parsed : parseResult.transactions()) {
            try {
                var hash = TransactionHashUtil.computeHash(
                        parsed.date(), parsed.type(), parsed.symbol(),
                        parsed.quantity(), parsed.amount());

                if (transactionRepository.existsByTenantIdAndAccountIdAndImportHash(
                        tenantId, accountId, hash)) {
                    skippedDuplicates++;
                    continue;
                }

                var request = new TransactionRequest(
                        parsed.date(), parsed.type(), parsed.symbol(),
                        parsed.quantity(), parsed.amount());
                transactionService.createWithHash(tenantId, accountId, request, hash);
                successCount++;
            } catch (Exception e) {
                log.warn("Failed to import transaction: {}", e.getMessage());
            }
        }

        job.setSuccessfulRows(successCount);
        job.setFailedRows(parseResult.errors().size() + (parseResult.transactions().size() - successCount - skippedDuplicates));
        job.setStatus("completed");
        var messages = new java.util.ArrayList<String>();
        if (!parseResult.errors().isEmpty()) {
            messages.add(parseResult.errors().size() + " rows had parse errors");
        }
        if (skippedDuplicates > 0) {
            messages.add(skippedDuplicates + " duplicate transactions skipped");
        }
        if (!messages.isEmpty()) {
            job.setErrorMessage(String.join("; ", messages));
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
