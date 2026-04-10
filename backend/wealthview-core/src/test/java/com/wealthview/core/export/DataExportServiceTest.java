package com.wealthview.core.export;

import com.wealthview.core.export.dto.TenantExportDto;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataExportServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private PropertyRepository propertyRepository;

    @InjectMocks
    private DataExportService dataExportService;

    private UUID tenantId;
    private TenantEntity tenant;
    private AccountEntity account;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new TenantEntity("Test Tenant");
        account = new AccountEntity(tenant, "Brokerage", "taxable", "Fidelity");
    }

    @Test
    void exportAsJson_returnsAllTenantData() {
        var holding = new HoldingEntity(account, tenant, "AAPL", new BigDecimal("10"), new BigDecimal("1500"));
        var transaction = new TransactionEntity(account, tenant, LocalDate.of(2024, 1, 15),
                "buy", "AAPL", new BigDecimal("10"), new BigDecimal("1500"));
        var property = new PropertyEntity(tenant, "123 Main St",
                new BigDecimal("300000"), LocalDate.of(2020, 6, 1),
                new BigDecimal("350000"), new BigDecimal("200000"));

        when(accountRepository.findByTenant_Id(tenantId)).thenReturn(List.of(account));
        when(transactionRepository.findByTenant_Id(tenantId)).thenReturn(List.of(transaction));
        when(holdingRepository.findByTenant_Id(tenantId)).thenReturn(List.of(holding));
        when(propertyRepository.findByTenant_Id(tenantId)).thenReturn(List.of(property));

        TenantExportDto result = dataExportService.exportAsJson(tenantId);

        assertThat(result.accounts()).hasSize(1);
        assertThat(result.accounts().getFirst().name()).isEqualTo("Brokerage");
        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().getFirst().symbol()).isEqualTo("AAPL");
        assertThat(result.holdings()).hasSize(1);
        assertThat(result.holdings().getFirst().symbol()).isEqualTo("AAPL");
        assertThat(result.properties()).hasSize(1);
        assertThat(result.properties().getFirst().address()).isEqualTo("123 Main St");
    }

    @Test
    void exportAsJson_emptyTenant_returnsEmptyLists() {
        when(accountRepository.findByTenant_Id(tenantId)).thenReturn(List.of());
        when(transactionRepository.findByTenant_Id(tenantId)).thenReturn(List.of());
        when(holdingRepository.findByTenant_Id(tenantId)).thenReturn(List.of());
        when(propertyRepository.findByTenant_Id(tenantId)).thenReturn(List.of());

        TenantExportDto result = dataExportService.exportAsJson(tenantId);

        assertThat(result.accounts()).isEmpty();
        assertThat(result.transactions()).isEmpty();
        assertThat(result.holdings()).isEmpty();
        assertThat(result.properties()).isEmpty();
    }

    @Test
    void exportAccountsCsv_containsHeaderAndData() {
        when(accountRepository.findByTenant_Id(tenantId)).thenReturn(List.of(account));

        String csv = dataExportService.exportAccountsCsv(tenantId);

        assertThat(csv).startsWith("id,name,type,institution,created_at\n");
        assertThat(csv).contains("Brokerage,taxable,Fidelity");
    }

    @Test
    void exportTransactionsCsv_containsHeaderAndData() {
        var transaction = new TransactionEntity(account, tenant, LocalDate.of(2024, 1, 15),
                "buy", "AAPL", new BigDecimal("10"), new BigDecimal("1500"));
        when(transactionRepository.findByTenant_Id(tenantId)).thenReturn(List.of(transaction));

        String csv = dataExportService.exportTransactionsCsv(tenantId);

        assertThat(csv).startsWith("id,account_id,date,type,symbol,quantity,amount,created_at\n");
        assertThat(csv).contains("2024-01-15,buy,AAPL,10,1500");
    }

    @Test
    void exportHoldingsCsv_containsHeaderAndData() {
        var holding = new HoldingEntity(account, tenant, "VOO", new BigDecimal("25"), new BigDecimal("10000"));
        when(holdingRepository.findByTenant_Id(tenantId)).thenReturn(List.of(holding));

        String csv = dataExportService.exportHoldingsCsv(tenantId);

        assertThat(csv).startsWith("id,account_id,symbol,quantity,cost_basis,is_manual_override,as_of_date\n");
        assertThat(csv).contains("VOO,25,10000");
    }

    @Test
    void exportPropertiesCsv_containsHeaderAndData() {
        var property = new PropertyEntity(tenant, "456 Oak Ave",
                new BigDecimal("500000"), LocalDate.of(2019, 3, 15),
                new BigDecimal("600000"), new BigDecimal("350000"));
        when(propertyRepository.findByTenant_Id(tenantId)).thenReturn(List.of(property));

        String csv = dataExportService.exportPropertiesCsv(tenantId);

        assertThat(csv).startsWith("id,address,purchase_price,purchase_date,current_value,mortgage_balance,property_type\n");
        assertThat(csv).contains("456 Oak Ave,500000,2019-03-15,600000,350000");
    }

    @Test
    void exportAccountsCsv_emptyList_returnsHeaderOnly() {
        when(accountRepository.findByTenant_Id(tenantId)).thenReturn(List.of());

        String csv = dataExportService.exportAccountsCsv(tenantId);

        assertThat(csv).isEqualTo("id,name,type,institution,created_at\n");
    }

    @Test
    void exportAccountsCsv_formulaInjection_prefixesWithSingleQuote() {
        var evil = new AccountEntity(tenant, "=cmd|'/C calc'!A0", "taxable", "@SUM(1+1)");
        when(accountRepository.findByTenant_Id(tenantId)).thenReturn(List.of(evil));

        String csv = dataExportService.exportAccountsCsv(tenantId);

        assertThat(csv).doesNotContain(",=cmd");
        assertThat(csv).doesNotContain(",@SUM");
        assertThat(csv).contains("\"'=cmd|'/C calc'!A0\"");
        assertThat(csv).contains("\"'@SUM(1+1)\"");
    }

    @Test
    void exportPropertiesCsv_leadingDashAndPlus_prefixesWithSingleQuote() {
        var property = new PropertyEntity(tenant, "-2+3",
                new BigDecimal("500000"), LocalDate.of(2019, 3, 15),
                new BigDecimal("600000"), new BigDecimal("350000"));
        property.setPropertyType("+1");
        when(propertyRepository.findByTenant_Id(tenantId)).thenReturn(List.of(property));

        String csv = dataExportService.exportPropertiesCsv(tenantId);

        assertThat(csv).contains("\"'-2+3\"");
        assertThat(csv).contains("\"'+1\"");
    }
}
