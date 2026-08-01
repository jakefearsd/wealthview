package com.wealthview.core.export;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

import static com.wealthview.persistence.entity.TransactionType.BUY;
import static com.wealthview.persistence.entity.TransactionType.DEPOSIT;
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
                BUY, "AAPL", new BigDecimal("10"), new BigDecimal("1500"));
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
                BUY, "AAPL", new BigDecimal("10"), new BigDecimal("1500"));
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

    // === RFC-4180 quoting ===
    //
    // The formula-injection tests above only ever supply values that get quoted as a SIDE EFFECT
    // of being neutralized. The quoting rule has three other triggers — an embedded comma, quote,
    // or newline — and none were exercised. These are ordinary values, not attacks: an account
    // named "Smith, John" or a note containing a line break silently shifts every later column of
    // that row if the quoting is wrong, corrupting the export without any error.

    @Test
    void exportAccountsCsv_valueContainingAComma_isQuotedSoColumnsDoNotShift() {
        var withComma = new AccountEntity(tenant, "Smith, John Joint", "taxable", "Fidelity");
        when(accountRepository.findByTenant_Id(tenantId)).thenReturn(List.of(withComma));

        String csv = dataExportService.exportAccountsCsv(tenantId);

        assertThat(csv).contains("\"Smith, John Joint\"");
        assertThat(csv.strip().lines().skip(1).findFirst().orElseThrow().split("\",?"))
                .as("the embedded comma must not be read as a field separator")
                .isNotEmpty();
    }

    @Test
    void exportAccountsCsv_valueContainingADoubleQuote_isQuotedAndTheQuoteDoubled() {
        var withQuote = new AccountEntity(tenant, "The \"Growth\" Account", "taxable", "Fidelity");
        when(accountRepository.findByTenant_Id(tenantId)).thenReturn(List.of(withQuote));

        String csv = dataExportService.exportAccountsCsv(tenantId);

        assertThat(csv)
                .as("RFC 4180 escapes an embedded quote by doubling it inside a quoted field")
                .contains("\"The \"\"Growth\"\" Account\"");
    }

    @Test
    void exportAccountsCsv_valueContainingANewline_isQuoted() {
        var withNewline = new AccountEntity(tenant, "Line one\nLine two", "taxable", "Fidelity");
        when(accountRepository.findByTenant_Id(tenantId)).thenReturn(List.of(withNewline));

        String csv = dataExportService.exportAccountsCsv(tenantId);

        assertThat(csv).contains("\"Line one\nLine two\"");
    }

    @Test
    void exportAccountsCsv_plainValue_isNotQuotedUnnecessarily() {
        var plain = new AccountEntity(tenant, "Brokerage", "taxable", "Fidelity");
        when(accountRepository.findByTenant_Id(tenantId)).thenReturn(List.of(plain));

        String csv = dataExportService.exportAccountsCsv(tenantId);

        assertThat(csv).contains(",Brokerage,").doesNotContain("\"Brokerage\"");
    }

    // === Injection vectors beyond the four leading symbols ===

    @Test
    void exportAccountsCsv_leadingTabOrCarriageReturn_isAlsoNeutralized() {
        // Excel treats a leading tab or CR the same as '=' once the cell is parsed, so both are in
        // the neutralization set — but neither was covered by a test.
        var tabbed = new AccountEntity(tenant, "\t=1+1", "taxable", "\r=2+2");
        when(accountRepository.findByTenant_Id(tenantId)).thenReturn(List.of(tabbed));

        String csv = dataExportService.exportAccountsCsv(tenantId);

        assertThat(csv).contains("'\t=1+1");
        assertThat(csv).contains("'\r=2+2");
    }

    @Test
    void exportAccountsCsv_emptyStringValue_isLeftAloneRatherThanPrefixed() {
        var blank = new AccountEntity(tenant, "", "taxable", "Fidelity");
        when(accountRepository.findByTenant_Id(tenantId)).thenReturn(List.of(blank));

        String csv = dataExportService.exportAccountsCsv(tenantId);

        assertThat(csv)
                .as("an empty cell has no leading character to neutralize")
                .doesNotContain("'");
    }

    @Test
    void exportAccountsCsv_nullValue_becomesAnEmptyFieldNotTheStringNull() {
        var nullInstitution = new AccountEntity(tenant, "Brokerage", "taxable", null);
        when(accountRepository.findByTenant_Id(tenantId)).thenReturn(List.of(nullInstitution));

        String csv = dataExportService.exportAccountsCsv(tenantId);

        assertThat(csv.lines().skip(1).findFirst().orElseThrow())
                .as("a null institution must collapse to an empty field between type and created_at")
                .contains(",Brokerage,taxable,,");
    }

    // === Transactions with no symbol or quantity ===

    @Test
    void exportTransactionsCsv_transactionWithoutSymbolOrQuantity_emitsEmptyFields() {
        // Cash movements (deposits, withdrawals, interest) carry neither a symbol nor a quantity.
        // Both ternaries guarding that were uncovered, so a "null" string leaking into the export
        // would not have been caught.
        var deposit = new TransactionEntity(account, tenant, LocalDate.of(2024, 2, 1),
                DEPOSIT, null, null, new BigDecimal("2500"));
        when(transactionRepository.findByTenant_Id(tenantId)).thenReturn(List.of(deposit));

        String csv = dataExportService.exportTransactionsCsv(tenantId);

        assertThat(csv)
                .as("symbol and quantity collapse to empty fields rather than the string \"null\"")
                .contains("2024-02-01,deposit,,,2500");
    }
}
