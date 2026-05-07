/**
 * JPA entities for WealthView. Entities backing tenant-owned data carry the
 * {@code tenantFilter} Hibernate filter, which is enabled per request by
 * {@code TenantFilterActivator} so that any query (including ones whose
 * developer-written WHERE clause is missing a {@code tenantId} predicate)
 * can only return rows owned by the authenticated tenant. SUPER_ADMIN
 * sessions and unauthenticated contexts (login, scheduled jobs) leave the
 * filter disabled and see all rows.
 */
@org.hibernate.annotations.FilterDef(
        name = "tenantFilter",
        parameters = @org.hibernate.annotations.ParamDef(name = "tenantId", type = java.util.UUID.class)
)
package com.wealthview.persistence.entity;
