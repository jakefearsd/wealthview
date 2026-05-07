package com.wealthview.core.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for service methods that intentionally access data across
 * tenant boundaries.
 *
 * <p>The {@code tenantFilter} Hibernate filter is enabled by
 * {@code TenantFilterActivator} for every authenticated non-SUPER_ADMIN
 * request, making cross-tenant rows physically invisible. SUPER_ADMIN
 * requests and unauthenticated contexts (login, scheduled jobs) leave the
 * filter disabled and therefore do not need this annotation.
 *
 * <p>Apply this annotation only when a code path that <em>could</em> be
 * reached by a regular ADMIN/MEMBER user must legitimately bypass tenant
 * isolation — for example, an invite-code redemption flow that has to look
 * up an invite by code before the user is associated with a tenant. The
 * companion {@code CrossTenantAspect} disables the filter for the duration
 * of the annotated method and re-enables it on exit (including on
 * exception).
 *
 * <p>Annotated methods MUST document <em>why</em> bypassing tenant scoping
 * is safe in their javadoc.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CrossTenant {
}
