package com.wealthview.api.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private List<String> allowedOrigins;

    @Value("${app.cookie.secure:true}")
    private boolean cookieSecure;

    /**
     * When true, {@code /actuator/prometheus} and {@code /actuator/metrics} are served without
     * authentication so an in-network Prometheus can scrape them. Default false — see the
     * comment on the matcher in {@link #securityFilterChain(HttpSecurity)} for the tradeoff.
     */
    @Value("${app.observability.anonymous-metrics:false}")
    private boolean anonymousMetrics;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // Spring Security 7 removed setSecure(boolean); drive the cookie's Secure
        // flag through the cookie customizer instead (identical behavior).
        csrfRepo.setCookieCustomizer(cookie -> cookie.secure(cookieSecure));
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        // Resolve the token from the X-XSRF-TOKEN header, not as a request parameter,
        // since we use the double-submit-cookie pattern with header echo.
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepo)
                        .csrfTokenRequestHandler(csrfHandler)
                        // CSRF protects cookie-authenticated requests from cross-site form
                        // submissions. Bearer tokens must be set explicitly by the app's
                        // HTTP client; an attacker page in a victim's browser cannot trigger
                        // that, so Bearer-authenticated requests don't need CSRF.
                        // Spring's CSRF filter runs before JwtAuthenticationFilter, so we
                        // re-check the Authorization header directly here rather than
                        // relying on the filter's request attribute.
                        .ignoringRequestMatchers(
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.POST, "/api/v1/auth/login"),
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.POST, "/api/v1/auth/register"),
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.POST, "/api/v1/auth/refresh"),
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.POST, "/api/v1/auth/mfa/challenge"),
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher("/api/v1/auth/token/**"),
                                bearerAuthorizationHeaderMatcher()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    // Opt-in anonymous metrics scraping, DEFAULT OFF.
                    //
                    // Prometheus cannot authenticate here: the filter chain accepts a JWT
                    // (bearer or cookie) and nothing else — there is no UserDetailsService and
                    // HTTP Basic is not enabled — so a scraper has no way to present
                    // SUPER_ADMIN credentials. The bundled observability stack was therefore
                    // getting 401 on every scrape and collecting nothing.
                    //
                    // Rather than introduce a second authentication mechanism just for metrics,
                    // the endpoints can be opened to in-network scraping explicitly. This is the
                    // standard pattern, but it means the metrics endpoints are UNAUTHENTICATED
                    // wherever it is switched on: only enable it when the app's port is not
                    // reachable from the internet, and keep /actuator blocked at the reverse
                    // proxy. See docs/OBSERVABILITY.md.
                    //
                    // Default (prod/dev/docker/it/default) is unchanged: /actuator/** stays
                    // SUPER_ADMIN-only, which PrometheusEndpointIT still asserts as 401/403/200.
                    // The loadtest profile sets this property in application-loadtest.yml; it
                    // used to be a hardcoded profile check here, and collapsing the two into one
                    // property keeps a single mechanism to reason about.
                    if (anonymousMetrics) {
                        auth.requestMatchers("/actuator/prometheus", "/actuator/metrics").permitAll();
                    }
                    auth
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/v1/auth/me").authenticated()
                        // Logout endpoints (cookie + Bearer) require an
                        // authenticated principal: the controller method
                        // resolves the user id from @AuthenticationPrincipal
                        // and would NPE if the request reached the controller
                        // unauthenticated. Without explicit authenticated()
                        // here, the wildcard permitAll below leaks a 500.
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/logout",
                                "/api/v1/auth/token/logout").authenticated()
                        // Per-device session endpoints (list / revoke) require
                        // the caller's principal — same reasoning as logout.
                        .requestMatchers("/api/v1/auth/sessions",
                                "/api/v1/auth/sessions/**").authenticated()
                        // MFA management endpoints all operate on the caller's
                        // own account; they are authenticated even though they
                        // are technically under /api/v1/auth/.
                        .requestMatchers("/api/v1/auth/mfa/setup",
                                "/api/v1/auth/mfa/verify-setup",
                                "/api/v1/auth/mfa/disable",
                                "/api/v1/auth/mfa/regenerate-recovery-codes",
                                "/api/v1/auth/mfa/status").authenticated()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Mobile force-update / version-check is anonymous: the
                        // app may not have credentials yet on launch, and the
                        // response reveals nothing sensitive (just the public
                        // version-policy values).
                        .requestMatchers(HttpMethod.GET, "/api/v1/app/version-check").permitAll()
                        .requestMatchers("/api/v1/admin/prices/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("SUPER_ADMIN")
                        // The audit log exposes every action taken in the tenant — other
                        // users' activity and admin operations included. The web UI only
                        // reaches it from the admin area, but the endpoint itself carried
                        // no matcher and fell through to anyRequest().authenticated(),
                        // so any member or viewer could read the whole trail directly over
                        // HTTP. UI-only gating is not access control.
                        .requestMatchers("/api/v1/audit-log", "/api/v1/audit-log/**")
                                .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/prices", "/api/v1/prices/**")
                                .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/prices/**")
                                .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/prices/**")
                                .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/tenant/invite-codes")
                                .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/tenant/invite-codes")
                                .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/tenant/invite-codes/*/revoke")
                                .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/tenant/invite-codes/used")
                                .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/tenant/users").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/tenant/users/*/role")
                                .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/tenant/users/*").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/**").hasAnyRole("ADMIN", "MEMBER", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/**").hasAnyRole("ADMIN", "MEMBER", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/**").hasAnyRole("ADMIN", "MEMBER", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .anyRequest().authenticated();
                })
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write("""
                                    {"error":"UNAUTHORIZED","message":"Authentication required","status":401}""");
                        })
                        // Without an explicit handler, Spring Security's default
                        // 403 path returns an empty body (or framework HTML),
                        // which breaks the {error,message,status} envelope used
                        // by every other endpoint. AccessDeniedException raised
                        // by the filter chain (e.g. role-mismatch on hasRole(...))
                        // never reaches @RestControllerAdvice, so we have to
                        // serialize the envelope here.
                        .accessDeniedHandler((request, response, deniedException) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            response.getWriter().write("""
                                    {"error":"FORBIDDEN","message":"Access denied","status":403}""");
                        })
                )
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31536000)
                                .includeSubDomains(true)
                                .preload(true))
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives(
                                        "default-src 'self'; script-src 'self';"
                                        + " style-src 'self' 'unsafe-inline';"
                                        + " img-src 'self' data:; font-src 'self'; connect-src 'self';"
                                        + " object-src 'none'; base-uri 'self';"
                                        + " form-action 'self'; frame-ancestors 'none'"))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                                "geolocation=(), microphone=(), camera=(), payment=()"))
                )
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    private static RequestMatcher bearerAuthorizationHeaderMatcher() {
        return request -> {
            var header = request.getHeader("Authorization");
            return header != null && header.startsWith("Bearer ");
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN"));
        configuration.setExposedHeaders(List.of("Set-Cookie"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
