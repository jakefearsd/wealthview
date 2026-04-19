package com.wealthview.app.it;

import com.wealthview.persistence.entity.InviteCodeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.InviteCodeRepository;
import com.wealthview.persistence.repository.TenantRepository;
import com.wealthview.persistence.repository.UserRepository;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Component
public class AuthHelper {

    private static final String ADMIN_EMAIL = "it-admin@wealthview.test";
    private static final String ADMIN_PASSWORD = "testpass123";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final PasswordEncoder passwordEncoder;

    private String cachedAdminToken;
    private UUID cachedTenantId;
    private UUID cachedAdminUserId;

    private String cachedTenant2Token;
    private UUID cachedTenant2Id;

    public AuthHelper(TenantRepository tenantRepository,
                      UserRepository userRepository,
                      InviteCodeRepository inviteCodeRepository,
                      PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.inviteCodeRepository = inviteCodeRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public void bootstrap(TestRestTemplate restTemplate) {
        bootstrapData();
        cachedAdminToken = login(restTemplate, ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    @Transactional
    public void bootstrapData() {
        var tenant = tenantRepository.save(new TenantEntity("IT Test Tenant"));
        cachedTenantId = tenant.getId();

        var admin = new UserEntity(tenant, ADMIN_EMAIL,
                passwordEncoder.encode(ADMIN_PASSWORD), "admin");
        admin = userRepository.save(admin);
        cachedAdminUserId = admin.getId();
    }

    public void bootstrapSecondTenant(TestRestTemplate restTemplate) {
        bootstrapSecondTenantData();
        cachedTenant2Token = login(restTemplate, "it-admin2@wealthview.test", ADMIN_PASSWORD);
    }

    @Transactional
    public void bootstrapSecondTenantData() {
        var tenant2 = tenantRepository.save(new TenantEntity("IT Test Tenant 2"));
        cachedTenant2Id = tenant2.getId();

        var admin2 = new UserEntity(tenant2, "it-admin2@wealthview.test",
                passwordEncoder.encode(ADMIN_PASSWORD), "admin");
        userRepository.save(admin2);
    }

    @Transactional
    public String createInviteCode() {
        var tenant = tenantRepository.findById(cachedTenantId).orElseThrow();
        var admin = userRepository.findById(cachedAdminUserId).orElseThrow();
        var code = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        var invite = new InviteCodeEntity(tenant, code, admin,
                OffsetDateTime.now().plusDays(7));
        inviteCodeRepository.save(invite);
        return code;
    }

    @Transactional
    public String createExpiredInviteCode() {
        var tenant = tenantRepository.findById(cachedTenantId).orElseThrow();
        var admin = userRepository.findById(cachedAdminUserId).orElseThrow();
        var code = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        var invite = new InviteCodeEntity(tenant, code, admin,
                OffsetDateTime.now().minusDays(1));
        inviteCodeRepository.save(invite);
        return code;
    }

    @Transactional
    public UUID createUserDirectly(String email, String password, String role) {
        var tenant = tenantRepository.findById(cachedTenantId).orElseThrow();
        var user = new UserEntity(tenant, email, passwordEncoder.encode(password), role);
        user = userRepository.save(user);
        return user.getId();
    }

    @Transactional
    public UUID createSuperAdminDirectly(String email, String password) {
        var tenant = tenantRepository.findById(cachedTenantId).orElseThrow();
        var user = new UserEntity(tenant, email, passwordEncoder.encode(password), "admin");
        user.setSuperAdmin(true);
        user = userRepository.save(user);
        return user.getId();
    }

    public String loginAs(TestRestTemplate restTemplate, String email, String password) {
        return login(restTemplate, email, password);
    }

    public String registerAndGetToken(TestRestTemplate restTemplate,
                                      String email, String password, String inviteCode) {
        var body = Map.of(
                "email", email,
                "password", password,
                "invite_code", inviteCode
        );
        var response = restTemplate.exchange("/api/v1/auth/register",
                HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        return (String) response.getBody().get("access_token");
    }

    public String adminToken() {
        return cachedAdminToken;
    }

    public UUID tenantId() {
        return cachedTenantId;
    }

    public UUID adminUserId() {
        return cachedAdminUserId;
    }

    public String tenant2Token() {
        return cachedTenant2Token;
    }

    public UUID tenant2Id() {
        return cachedTenant2Id;
    }

    public HttpHeaders authHeaders(String token) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    public <T> HttpEntity<T> authEntity(T body, String token) {
        return new HttpEntity<>(body, authHeaders(token));
    }

    public HttpEntity<Void> authEntity(String token) {
        return new HttpEntity<>(authHeaders(token));
    }

    private String login(TestRestTemplate restTemplate, String email, String password) {
        var body = Map.of("email", email, "password", password);
        var response = restTemplate.exchange("/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        return (String) response.getBody().get("access_token");
    }

    private HttpHeaders jsonHeaders() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
