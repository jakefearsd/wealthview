package com.wealthview.core.importservice;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.holding.HoldingsComputationService;
import com.wealthview.core.importservice.dto.CsvParseResult;
import com.wealthview.core.importservice.dto.ImportJobResponse;
import com.wealthview.core.importservice.dto.ImportResult;
import com.wealthview.core.importservice.dto.ParsedTransaction;
import com.wealthview.core.transaction.TransactionService;
import com.wealthview.core.transaction.dto.TransactionRequest;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.ImportJobEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.ImportJobRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ImportJobRepository importJobRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final HoldingsComputationService holdingsComputationService;
    private final CsvParser csvParser;
    private final Map<String, CsvParser> namedParsers;

    public ImportService(ImportJobRepository importJobRepository,
                         AccountRepository accountRepository,
                         TransactionRepository transactionRepository,
                         TransactionService transactionService,
                         HoldingsComputationService holdingsComputationService,
                         CsvParser csvParser,
                         Map<String, CsvParser> namedParsers) {
        this.importJobRepository = importJobRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.transactionService = transactionService;
        this.holdingsComputationService = holdingsComputationService;
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

    CsvParser resolveParser(String format) {
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
    public ImportJobResponse importOfx(UUID tenantId, UUID accountId, InputStream inputStream) throws IOException {
        var ofxParser = namedParsers.get("ofxParser");
        if (ofxParser == null) {
            throw new IllegalStateException("OFX parser not available");
        }
        var parseResult = ofxParser.parse(inputStream);
        return processImport(tenantId, accountId, parseResult, "ofx");
    }

    @Transactional
    public ImportJobResponse processCsvImport(UUID tenantId, UUID accountId, CsvParseResult parseResult) {
        return processImport(tenantId, accountId, parseResult, "csv");
    }

    @Transactional
    public ImportJobResponse processImport(UUID tenantId, UUID accountId,
                                            CsvParseResult parseResult, String source) {
        MDC.put("operation", "import");
        MDC.put("importFormat", source);
        try {
            log.info("Starting {} import for account {}: {} transactions parsed, {} parse errors",
                    source.toUpperCase(Locale.US), accountId, parseResult.transactions().size(), parseResult.errors().size());

            var account = accountRepository.findByTenant_IdAndId(tenantId, accountId)
                    .orElseThrow(() -> new EntityNotFoundException("Account not found"));

            var job = new ImportJobEntity(account.getTenant(), account, source);
            job.setStatus("processing");
            job.setTotalRows(parseResult.transactions().size() + parseResult.errors().size());
            job = importJobRepository.save(job);

            var result = importTransactions(parseResult.transactions(), tenantId, accountId, account);
            finalizeJob(job, result, parseResult.errors().size());

            log.info("{} import completed for account {}: {} successful, {} duplicates skipped, {} failed",
                    source.toUpperCase(Locale.US), accountId, result.successCount(), result.skippedDuplicates(), job.getFailedRows());
            return ImportJobResponse.from(job);
        } finally {
            MDC.remove("operation");
            MDC.remove("importFormat");
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // intentional catch-all so one bad row doesn't abort entire import
    private ImportResult importTransactions(List<ParsedTransaction> transactions,
                                             UUID tenantId, UUID accountId,
                                             AccountEntity account) {
        // Batch compute all hashes and check duplicates in one query
        var hashToTransaction = new LinkedHashMap<String, ParsedTransaction>();
        for (var parsed : transactions) {
            var hash = TransactionHashUtil.computeHash(
                    parsed.date(), parsed.type(), parsed.symbol(),
                    parsed.quantity(), parsed.amount());
            hashToTransaction.put(hash, parsed);
        }

        var existingHashes = transactionRepository.findExistingImportHashes(
                tenantId, accountId, hashToTransaction.keySet());

        int successCount = 0;
        int skippedDuplicates = 0;
        int failedCount = 0;
        Set<String> affectedSymbols = new HashSet<>();

        for (var entry : hashToTransaction.entrySet()) {
            var hash = entry.getKey();
            var parsed = entry.getValue();

            if (existingHashes.contains(hash)) {
                log.debug("Skipped duplicate transaction: hash={}", hash);
                skippedDuplicates++;
                continue;
            }

            try {
                var request = new TransactionRequest(
                        parsed.date(), parsed.type(), parsed.symbol(),
                        parsed.quantity(), parsed.amount());
                transactionService.createWithHashNoRecompute(tenantId, accountId, request, hash);
                successCount++;
                if (parsed.symbol() != null) {
                    affectedSymbols.add(parsed.symbol());
                }
            } catch (Exception e) {
                log.warn("Failed to import transaction", e);
                failedCount++;
            }
        }

        // Recompute holdings once per distinct symbol at the end
        for (var symbol : affectedSymbols) {
            holdingsComputationService.recomputeForAccountAndSymbol(
                    account, account.getTenant(), symbol);
        }

        return new ImportResult(successCount, skippedDuplicates, failedCount);
    }

    private void finalizeJob(ImportJobEntity job, ImportResult result, int parseErrorCount) {
        job.setSuccessfulRows(result.successCount());
        job.setFailedRows(parseErrorCount + result.failedCount());
        job.setStatus("completed");

        var messages = new ArrayList<String>();
        if (parseErrorCount > 0) {
            messages.add(parseErrorCount + " rows had parse errors");
        }
        if (result.skippedDuplicates() > 0) {
            messages.add(result.skippedDuplicates() + " duplicate transactions skipped");
        }
        if (!messages.isEmpty()) {
            job.setErrorMessage(String.join("; ", messages));
        }
        job.setUpdatedAt(OffsetDateTime.now());
        importJobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public List<ImportJobResponse> listJobs(UUID tenantId) {
        return importJobRepository.findByTenant_Id(tenantId).stream()
                .map(ImportJobResponse::from)
                .toList();
    }
}
