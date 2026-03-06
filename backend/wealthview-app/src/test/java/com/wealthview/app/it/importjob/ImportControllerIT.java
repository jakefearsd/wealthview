package com.wealthview.app.it.importjob;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImportControllerIT extends AbstractApiIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private String accountId;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        authHelper.bootstrap(restTemplate);
        accountId = createAccount();
    }

    @Test
    void importCsv_genericFormat_returns201() {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ClassPathResource("test-data/generic-transactions.csv"));
        body.add("accountId", accountId);
        body.add("format", "generic");

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(authHelper.adminToken());

        var response = restTemplate.exchange("/api/v1/import/csv",
                HttpMethod.POST, new HttpEntity<>(body, headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("id");
    }

    @Test
    void importCsv_duplicateFile_detectsDuplicates() {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ClassPathResource("test-data/generic-transactions.csv"));
        body.add("accountId", accountId);
        body.add("format", "generic");

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(authHelper.adminToken());

        // First import
        restTemplate.exchange("/api/v1/import/csv",
                HttpMethod.POST, new HttpEntity<>(body, headers), MAP_TYPE);

        // Second import of same file
        var body2 = new LinkedMultiValueMap<String, Object>();
        body2.add("file", new ClassPathResource("test-data/generic-transactions.csv"));
        body2.add("accountId", accountId);
        body2.add("format", "generic");

        var response = restTemplate.exchange("/api/v1/import/csv",
                HttpMethod.POST, new HttpEntity<>(body2, headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var totalRows = ((Number) response.getBody().get("total_rows")).intValue();
        var successfulRows = ((Number) response.getBody().get("successful_rows")).intValue();
        // On duplicate import, some or all rows should be detected as duplicates (failed)
        assertThat(totalRows).isGreaterThan(0);
        assertThat(successfulRows).isLessThan(totalRows);
    }

    @Test
    void importCsv_invalidFormat_returns400() {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ClassPathResource("test-data/generic-transactions.csv"));
        body.add("accountId", accountId);
        body.add("format", "nonexistent_format");

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(authHelper.adminToken());

        var response = restTemplate.exchange("/api/v1/import/csv",
                HttpMethod.POST, new HttpEntity<>(body, headers), MAP_TYPE);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }

    private String createAccount() {
        var body = Map.of("name", "Import Test Account", "type", "brokerage");
        var response = restTemplate.exchange("/api/v1/accounts",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
        return (String) response.getBody().get("id");
    }
}
