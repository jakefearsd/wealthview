package com.wealthview.core.export;

import com.wealthview.core.account.dto.AccountResponse;
import com.wealthview.core.export.dto.TenantExportDto;
import com.wealthview.core.holding.dto.HoldingResponse;
import com.wealthview.core.property.dto.PropertyResponse;
import com.wealthview.core.transaction.dto.TransactionResponse;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@Service
@Transactional(readOnly = true)
public class DataExportService {

    private static final Logger log = LoggerFactory.getLogger(DataExportService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final HoldingRepository holdingRepository;
    private final PropertyRepository propertyRepository;

    public DataExportService(AccountRepository accountRepository,
                             TransactionRepository transactionRepository,
                             HoldingRepository holdingRepository,
                             PropertyRepository propertyRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.holdingRepository = holdingRepository;
        this.propertyRepository = propertyRepository;
    }

    public TenantExportDto exportAsJson(UUID tenantId) {
        log.info("Starting JSON export for tenant {}", tenantId);
        var accounts = accountRepository.findByTenant_Id(tenantId).stream()
                .map(a -> AccountResponse.from(a, BigDecimal.ZERO))
                .toList();
        var transactions = transactionRepository.findByTenant_Id(tenantId).stream()
                .map(TransactionResponse::from)
                .toList();
        var holdings = holdingRepository.findByTenant_Id(tenantId).stream()
                .map(HoldingResponse::from)
                .toList();
        var properties = propertyRepository.findByTenant_Id(tenantId).stream()
                .map(p -> PropertyResponse.from(p, p.getMortgageBalance()))
                .toList();
        log.info("JSON export complete for tenant {}: {} accounts, {} transactions, {} holdings, {} properties",
                tenantId, accounts.size(), transactions.size(), holdings.size(), properties.size());
        return new TenantExportDto(accounts, transactions, holdings, properties);
    }

    public String exportAccountsCsv(UUID tenantId) {
        log.info("Starting accounts CSV export for tenant {}", tenantId);
        return toCsv("id,name,type,institution,created_at",
                accountRepository.findByTenant_Id(tenantId),
                a -> String.join(",",
                        String.valueOf(a.getId()),
                        csvEscape(a.getName()),
                        csvEscape(a.getType()),
                        csvEscape(a.getInstitution()),
                        String.valueOf(a.getCreatedAt())));
    }

    public String exportTransactionsCsv(UUID tenantId) {
        log.info("Starting transactions CSV export for tenant {}", tenantId);
        return toCsv("id,account_id,date,type,symbol,quantity,amount,created_at",
                transactionRepository.findByTenant_Id(tenantId),
                t -> String.join(",",
                        String.valueOf(t.getId()),
                        String.valueOf(t.getAccountId()),
                        String.valueOf(t.getDate()),
                        csvEscape(t.getType()),
                        t.getSymbol() != null ? t.getSymbol() : "",
                        t.getQuantity() != null ? t.getQuantity().toString() : "",
                        t.getAmount().toString(),
                        String.valueOf(t.getCreatedAt())));
    }

    public String exportHoldingsCsv(UUID tenantId) {
        log.info("Starting holdings CSV export for tenant {}", tenantId);
        return toCsv("id,account_id,symbol,quantity,cost_basis,is_manual_override,as_of_date",
                holdingRepository.findByTenant_Id(tenantId),
                h -> String.join(",",
                        String.valueOf(h.getId()),
                        String.valueOf(h.getAccountId()),
                        csvEscape(h.getSymbol()),
                        h.getQuantity().toString(),
                        h.getCostBasis().toString(),
                        String.valueOf(h.isManualOverride()),
                        String.valueOf(h.getAsOfDate())));
    }

    public String exportPropertiesCsv(UUID tenantId) {
        log.info("Starting properties CSV export for tenant {}", tenantId);
        return toCsv("id,address,purchase_price,purchase_date,current_value,mortgage_balance,property_type",
                propertyRepository.findByTenant_Id(tenantId),
                p -> String.join(",",
                        String.valueOf(p.getId()),
                        csvEscape(p.getAddress()),
                        p.getPurchasePrice().toString(),
                        String.valueOf(p.getPurchaseDate()),
                        p.getCurrentValue().toString(),
                        p.getMortgageBalance().toString(),
                        csvEscape(p.getPropertyType())));
    }

    private <T> String toCsv(String header, List<T> entities, Function<T, String> rowFormatter) {
        var sb = new StringBuilder(header).append('\n');
        for (T entity : entities) {
            sb.append(rowFormatter.apply(entity)).append('\n');
        }
        return sb.toString();
    }

    private String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
