package com.wealthview.app.it;

import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.wealthview.app.WealthViewApplication;
import com.wealthview.app.it.testutil.ApiClient;
import com.wealthview.app.it.testutil.TestDataHelper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = WealthViewApplication.class
)
// Boot 4 moved TestRestTemplate into spring-boot-resttestclient; @SpringBootTest no
// longer registers the bean automatically, so opt in explicitly.
@AutoConfigureTestRestTemplate
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

    /**
     * Authenticated HTTP client for the bootstrapped admin (with {@code *As} variants
     * for explicit tokens). Initialized in {@link #configureRestTemplate()} rather than
     * {@link #setUp()} so subclasses that suppress the per-test setUp (e.g. ordered
     * PER_CLASS suites) can still use it; tokens are resolved per call.
     */
    protected ApiClient api;

    @PostConstruct
    void configureRestTemplate() {
        // Use Apache HttpClient5, but disable automatic cookie retention so each
        // request is independent — auth cookies must be supplied explicitly per
        // request via AuthHelper#authHeaders. Without this, the cookie jar would
        // make `HttpEntity.EMPTY` requests appear authenticated as soon as one
        // earlier login in the same test ran.
        // Spring Framework 7 removed HttpComponentsClientHttpRequestFactory#setConnectTimeout;
        // the TCP connect timeout is now configured on the HttpClient5 connection manager.
        var connectionManager = org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(org.apache.hc.client5.http.config.ConnectionConfig.custom()
                        .setConnectTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(15))
                        // Without this, a pooled connection that the embedded Tomcat has already
                        // closed (e.g. after a preceding request in the same test) can be handed
                        // back out and written to before the client notices it's dead: the write
                        // succeeds at the TCP layer, the server responds with a bare status line
                        // and no body (Content-Length: 0, Connection: close), and HttpClient5's
                        // transparent single retry sometimes masks it and sometimes doesn't —
                        // producing an assertion failure on the response body maybe 1 run in 3.
                        // Forcing validation before every reuse (ZERO_MILLISECONDS = always check)
                        // trades a cheap non-blocking peek for eliminating that race; this suite is
                        // not latency-sensitive. Root-caused while adding StockSplitSyncUnavailableIT.
                        .setValidateAfterInactivity(org.apache.hc.core5.util.TimeValue.ZERO_MILLISECONDS)
                        .build())
                .build();
        var httpClient = org.apache.hc.client5.http.impl.classic.HttpClientBuilder.create()
                .disableCookieManagement()
                .setConnectionManager(connectionManager)
                .build();
        var requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        // Generous, explicit read timeout. CI runs on small shared (2-core) GitHub-hosted
        // runners where the full Testcontainers suite contends for CPU, so heavier
        // requests (e.g. stock-split unapply, or a GET while a backfill runs) can be
        // slow; the default socket timeout otherwise trips as "Read timed out".
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(90));
        restTemplate.getRestTemplate().setRequestFactory(requestFactory);
        api = new ApiClient(restTemplate, authHelper);
    }

    @BeforeEach
    protected void setUp() {
        // Defense-in-depth against SecurityContext leakage between IT classes:
        // JUnit runs every test on the same worker thread, and a test that
        // plants an Authentication there (e.g. TenantFilterBackstopIT) would
        // otherwise tenant-filter service-level @Transactional calls in every
        // LATER class that invokes services directly instead of over HTTP —
        // the root cause of the deterministic StockSplitBackfillIT failures on
        // hosted CI (runs 29195289770..29203911100).
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        databaseCleaner.clean();
        authHelper.bootstrap(restTemplate);
        data = new TestDataHelper(restTemplate, authHelper);
    }
}
