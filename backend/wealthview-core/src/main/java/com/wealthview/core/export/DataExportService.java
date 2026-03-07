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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class DataExportService {

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
        return new TenantExportDto(accounts, transactions, holdings, properties);
    }

    public String exportAccountsCsv(UUID tenantId) {
        var sb = new StringBuilder("id,name,type,institution,created_at\n");
        for (AccountEntity a : accountRepository.findByTenant_Id(tenantId)) {
            sb.append(a.getId()).append(',')
                    .append(a.getName()).append(',')
                    .append(a.getType()).append(',')
                    .append(a.getInstitution()).append(',')
                    .append(a.getCreatedAt()).append('\n');
        }
        return sb.toString();
    }

    public String exportTransactionsCsv(UUID tenantId) {
        var sb = new StringBuilder("id,account_id,date,type,symbol,quantity,amount,created_at\n");
        for (TransactionEntity t : transactionRepository.findByTenant_Id(tenantId)) {
            sb.append(t.getId()).append(',')
                    .append(t.getAccountId()).append(',')
                    .append(t.getDate()).append(',')
                    .append(t.getType()).append(',')
                    .append(t.getSymbol() != null ? t.getSymbol() : "").append(',')
                    .append(t.getQuantity() != null ? t.getQuantity() : "").append(',')
                    .append(t.getAmount()).append(',')
                    .append(t.getCreatedAt()).append('\n');
        }
        return sb.toString();
    }

    public String exportHoldingsCsv(UUID tenantId) {
        var sb = new StringBuilder("id,account_id,symbol,quantity,cost_basis,is_manual_override,as_of_date\n");
        for (HoldingEntity h : holdingRepository.findByTenant_Id(tenantId)) {
            sb.append(h.getId()).append(',')
                    .append(h.getAccountId()).append(',')
                    .append(h.getSymbol()).append(',')
                    .append(h.getQuantity()).append(',')
                    .append(h.getCostBasis()).append(',')
                    .append(h.isManualOverride()).append(',')
                    .append(h.getAsOfDate()).append('\n');
        }
        return sb.toString();
    }

    public String exportPropertiesCsv(UUID tenantId) {
        var sb = new StringBuilder("id,address,purchase_price,purchase_date,current_value,mortgage_balance,property_type\n");
        for (PropertyEntity p : propertyRepository.findByTenant_Id(tenantId)) {
            sb.append(p.getId()).append(',')
                    .append(p.getAddress()).append(',')
                    .append(p.getPurchasePrice()).append(',')
                    .append(p.getPurchaseDate()).append(',')
                    .append(p.getCurrentValue()).append(',')
                    .append(p.getMortgageBalance()).append(',')
                    .append(p.getPropertyType()).append('\n');
        }
        return sb.toString();
    }
}
