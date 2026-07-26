package com.wealthview.api.testutil;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.SessionStateValidator;

/**
 * Composed annotation for {@code @WebMvcTest} controller tests. Bundles the four-class
 * {@code @Import} set (security config, global exception handler, JWT filter, test metrics) and
 * the placeholder {@code JwtTokenProvider}/{@code SessionStateValidator} mocks that
 * {@code JwtAuthenticationFilter} needs present in the context -- a verbatim block repeated
 * across 34 controller/security test classes. Equivalent to:
 *
 * <pre>
 * {@literal @}WebMvcTest(FooController.class)
 * {@literal @}Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class,
 *         TestMetricsConfig.class})
 * {@literal @}MockitoBean(types = {JwtTokenProvider.class, SessionStateValidator.class})
 * </pre>
 *
 * <p>Tests that actually stub {@code JwtTokenProvider} / {@code SessionStateValidator} behavior
 * (not just needing them present as no-op beans) should keep field-level {@code @MockitoBean}
 * declarations instead -- see {@code AuthControllerTest}, which stays on the raw annotations
 * because mixing a type-level and a field-level {@code @MockitoBean} for the same type is not a
 * combination worth relying on.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@WebMvcTest
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
@MockitoBean(types = {JwtTokenProvider.class, SessionStateValidator.class})
public @interface WealthViewControllerTest {

    @AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
    Class<?>[] value() default {};

}
