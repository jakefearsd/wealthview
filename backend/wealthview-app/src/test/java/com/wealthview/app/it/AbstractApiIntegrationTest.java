package com.wealthview.app.it;

import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.wealthview.app.WealthViewApplication;
import com.wealthview.app.it.testutil.TestDataHelper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = WealthViewApplication.class
)
@ActiveProfiles("it")
public abstract class AbstractApiIntegrationTest {

    static final PostgreSQLContainer POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer("postgres:16")
                .withDatabaseName("wealthview_it")
                .withUsername("test")
                .withPassword("test");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected AuthHelper authHelper;

    @Autowired
    protected DatabaseCleaner databaseCleaner;

    protected TestDataHelper data;

    @PostConstruct
    void configureRestTemplate() {
        // Use Apache HttpClient5, but disable automatic cookie retention so each
        // request is independent — auth cookies must be supplied explicitly per
        // request via AuthHelper#authHeaders. Without this, the cookie jar would
        // make `HttpEntity.EMPTY` requests appear authenticated as soon as one
        // earlier login in the same test ran.
        var httpClient = org.apache.hc.client5.http.impl.classic.HttpClientBuilder.create()
                .disableCookieManagement()
                .build();
        var requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        // Generous, explicit timeouts. CI runs on small shared (2-core) GitHub-hosted
        // runners where the full Testcontainers suite contends for CPU, so heavier
        // requests (e.g. stock-split unapply, or a GET while a backfill runs) can be
        // slow; the default socket timeout otherwise trips as "Read timed out".
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(15));
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(90));
        restTemplate.getRestTemplate().setRequestFactory(requestFactory);
    }

    @BeforeEach
    protected void setUp() {
        databaseCleaner.clean();
        authHelper.bootstrap(restTemplate);
        data = new TestDataHelper(restTemplate, authHelper);
    }
}
