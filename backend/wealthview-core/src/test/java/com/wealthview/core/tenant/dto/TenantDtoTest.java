package com.wealthview.core.tenant.dto;

import com.wealthview.persistence.entity.InviteCodeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.UserEntity;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for tenant-module DTOs. Covers:
 * <ul>
 *   <li>Entity → response factory methods (AdminUserResponse, InviteCodeResponse,
 *       TenantResponse, UserResponse).</li>
 *   <li>The expiryDaysOrDefault() fallback on GenerateInviteRequest.</li>
 *   <li>Bean Validation constraints on request DTOs (PasswordResetRequest,
 *       SetActiveRequest, TenantRequest, UpdateRoleRequest).</li>
 * </ul>
 */
class TenantDtoTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // ───────── AdminUserResponse.from ─────────

    @Test
    void adminUserResponseFrom_withAllFieldsPopulated_mapsEveryField() {
        var tenantId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var createdAt = OffsetDateTime.parse("2026-04-01T00:00:00Z");

        var tenant = mock(TenantEntity.class);
        when(tenant.getId()).thenReturn(tenantId);
        when(tenant.getName()).thenReturn("Acme Corp");

        var entity = mock(UserEntity.class);
        when(entity.getId()).thenReturn(userId);
        when(entity.getEmail()).thenReturn("admin@acme.com");
        when(entity.getRole()).thenReturn("admin");
        when(entity.getTenant()).thenReturn(tenant);
        when(entity.isActive()).thenReturn(true);
        when(entity.getCreatedAt()).thenReturn(createdAt);

        var response = AdminUserResponse.from(entity);

        assertThat(response.id()).isEqualTo(userId);
        assertThat(response.email()).isEqualTo("admin@acme.com");
        assertThat(response.role()).isEqualTo("admin");
        assertThat(response.tenantId()).isEqualTo(tenantId);
        assertThat(response.tenantName()).isEqualTo("Acme Corp");
        assertThat(response.isActive()).isTrue();
        assertThat(response.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void adminUserResponseFrom_withInactiveUser_reportsInactive() {
        var entity = mock(UserEntity.class);
        when(entity.getId()).thenReturn(UUID.randomUUID());
        when(entity.getEmail()).thenReturn("old@acme.com");
        when(entity.getRole()).thenReturn("member");
        when(entity.getTenant()).thenReturn(mock(TenantEntity.class));
        when(entity.isActive()).thenReturn(false);
        when(entity.getCreatedAt()).thenReturn(OffsetDateTime.now());

        var response = AdminUserResponse.from(entity);

        assertThat(response.isActive()).isFalse();
    }

    // ───────── InviteCodeResponse.from ─────────

    @Test
    void inviteCodeResponseFrom_withConsumedCode_populatesUsedByEmail() {
        var consumer = mock(UserEntity.class);
        when(consumer.getEmail()).thenReturn("newbie@acme.com");

        var creator = mock(UserEntity.class);
        when(creator.getEmail()).thenReturn("admin@acme.com");

        var entity = mock(InviteCodeEntity.class);
        var id = UUID.randomUUID();
        var expires = OffsetDateTime.parse("2026-05-01T00:00:00Z");
        var created = OffsetDateTime.parse("2026-04-01T00:00:00Z");
        when(entity.getId()).thenReturn(id);
        when(entity.getCode()).thenReturn("INVITE-1234");
        when(entity.getExpiresAt()).thenReturn(expires);
        when(entity.isConsumed()).thenReturn(true);
        when(entity.isRevoked()).thenReturn(false);
        when(entity.getConsumedBy()).thenReturn(consumer);
        when(entity.getCreatedBy()).thenReturn(creator);
        when(entity.getCreatedAt()).thenReturn(created);

        var response = InviteCodeResponse.from(entity);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.code()).isEqualTo("INVITE-1234");
        assertThat(response.expiresAt()).isEqualTo(expires);
        assertThat(response.consumed()).isTrue();
        assertThat(response.isRevoked()).isFalse();
        assertThat(response.usedByEmail()).isEqualTo("newbie@acme.com");
        assertThat(response.createdByEmail()).isEqualTo("admin@acme.com");
        assertThat(response.createdAt()).isEqualTo(created);
    }

    @Test
    void inviteCodeResponseFrom_withUnconsumedCode_leavesUsedByEmailNull() {
        var creator = mock(UserEntity.class);
        when(creator.getEmail()).thenReturn("admin@acme.com");

        var entity = mock(InviteCodeEntity.class);
        when(entity.getId()).thenReturn(UUID.randomUUID());
        when(entity.getCode()).thenReturn("INVITE-5555");
        when(entity.getExpiresAt()).thenReturn(OffsetDateTime.now());
        when(entity.isConsumed()).thenReturn(false);
        when(entity.isRevoked()).thenReturn(false);
        when(entity.getConsumedBy()).thenReturn(null);
        when(entity.getCreatedBy()).thenReturn(creator);
        when(entity.getCreatedAt()).thenReturn(OffsetDateTime.now());

        var response = InviteCodeResponse.from(entity);

        assertThat(response.usedByEmail()).isNull();
        assertThat(response.createdByEmail()).isEqualTo("admin@acme.com");
    }

    @Test
    void inviteCodeResponseFrom_withNullCreator_leavesCreatedByEmailNull() {
        // Creator is non-null in the DB constraint but the factory guards against null.
        var entity = mock(InviteCodeEntity.class);
        when(entity.getId()).thenReturn(UUID.randomUUID());
        when(entity.getCode()).thenReturn("INVITE-0000");
        when(entity.getExpiresAt()).thenReturn(OffsetDateTime.now());
        when(entity.isConsumed()).thenReturn(false);
        when(entity.isRevoked()).thenReturn(false);
        when(entity.getConsumedBy()).thenReturn(null);
        when(entity.getCreatedBy()).thenReturn(null);
        when(entity.getCreatedAt()).thenReturn(OffsetDateTime.now());

        var response = InviteCodeResponse.from(entity);

        assertThat(response.createdByEmail()).isNull();
    }

    // ───────── TenantResponse.from ─────────

    @Test
    void tenantResponseFrom_mapsIdNameAndCreatedAt() {
        var id = UUID.randomUUID();
        var createdAt = OffsetDateTime.parse("2026-01-15T12:00:00Z");
        var entity = mock(TenantEntity.class);
        when(entity.getId()).thenReturn(id);
        when(entity.getName()).thenReturn("Demo Tenant");
        when(entity.getCreatedAt()).thenReturn(createdAt);

        var response = TenantResponse.from(entity);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.name()).isEqualTo("Demo Tenant");
        assertThat(response.createdAt()).isEqualTo(createdAt);
    }

    // ───────── UserResponse.from ─────────

    @Test
    void userResponseFrom_mapsAllFields() {
        var id = UUID.randomUUID();
        var createdAt = OffsetDateTime.parse("2026-02-01T00:00:00Z");
        var entity = mock(UserEntity.class);
        when(entity.getId()).thenReturn(id);
        when(entity.getEmail()).thenReturn("user@demo.com");
        when(entity.getRole()).thenReturn("member");
        when(entity.getCreatedAt()).thenReturn(createdAt);

        var response = UserResponse.from(entity);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.email()).isEqualTo("user@demo.com");
        assertThat(response.role()).isEqualTo("member");
        assertThat(response.createdAt()).isEqualTo(createdAt);
    }

    // ───────── GenerateInviteRequest.expiryDaysOrDefault ─────────

    @Test
    void expiryDaysOrDefault_withNullValue_returnsSeven() {
        var request = new GenerateInviteRequest(null);

        assertThat(request.expiryDaysOrDefault()).isEqualTo(7);
    }

    @Test
    void expiryDaysOrDefault_withExplicitValue_returnsThatValue() {
        var request = new GenerateInviteRequest(30);

        assertThat(request.expiryDaysOrDefault()).isEqualTo(30);
    }

    // ───────── PasswordResetRequest validation ─────────

    @Test
    void passwordResetRequest_withValidPassword_hasNoViolations() {
        Set<ConstraintViolation<PasswordResetRequest>> violations =
                validator.validate(new PasswordResetRequest("supersecret-12-chars"));

        assertThat(violations).isEmpty();
    }

    @Test
    void passwordResetRequest_withTooShortPassword_reportsSizeViolation() {
        Set<ConstraintViolation<PasswordResetRequest>> violations =
                validator.validate(new PasswordResetRequest("short"));

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .anyMatch(msg -> msg.contains("between 12 and 64"));
    }

    @Test
    void passwordResetRequest_withBlankPassword_reportsNotBlank() {
        Set<ConstraintViolation<PasswordResetRequest>> violations =
                validator.validate(new PasswordResetRequest(""));

        assertThat(violations).isNotEmpty();
    }

    // ───────── SetActiveRequest validation ─────────

    @Test
    void setActiveRequest_withNullActive_reportsNotNull() {
        Set<ConstraintViolation<SetActiveRequest>> violations =
                validator.validate(new SetActiveRequest(null));

        assertThat(violations).hasSize(1);
    }

    @Test
    void setActiveRequest_withTrue_hasNoViolations() {
        Set<ConstraintViolation<SetActiveRequest>> violations =
                validator.validate(new SetActiveRequest(true));

        assertThat(violations).isEmpty();
    }

    // ───────── TenantRequest validation ─────────

    @Test
    void tenantRequest_withBlankName_reportsNotBlank() {
        Set<ConstraintViolation<TenantRequest>> violations =
                validator.validate(new TenantRequest(""));

        assertThat(violations).isNotEmpty();
    }

    @Test
    void tenantRequest_withValidName_hasNoViolations() {
        Set<ConstraintViolation<TenantRequest>> violations =
                validator.validate(new TenantRequest("Acme Corp"));

        assertThat(violations).isEmpty();
    }

    // ───────── UpdateRoleRequest validation ─────────

    @Test
    void updateRoleRequest_withAdminRole_hasNoViolations() {
        Set<ConstraintViolation<UpdateRoleRequest>> violations =
                validator.validate(new UpdateRoleRequest("admin"));

        assertThat(violations).isEmpty();
    }

    @Test
    void updateRoleRequest_withMemberRole_hasNoViolations() {
        Set<ConstraintViolation<UpdateRoleRequest>> violations =
                validator.validate(new UpdateRoleRequest("member"));

        assertThat(violations).isEmpty();
    }

    @Test
    void updateRoleRequest_withViewerRole_hasNoViolations() {
        Set<ConstraintViolation<UpdateRoleRequest>> violations =
                validator.validate(new UpdateRoleRequest("viewer"));

        assertThat(violations).isEmpty();
    }

    @Test
    void updateRoleRequest_withUnknownRole_reportsPatternViolation() {
        Set<ConstraintViolation<UpdateRoleRequest>> violations =
                validator.validate(new UpdateRoleRequest("super-duper-admin"));

        assertThat(violations).isNotEmpty();
    }

    @Test
    void updateRoleRequest_withBlankRole_reportsNotBlank() {
        Set<ConstraintViolation<UpdateRoleRequest>> violations =
                validator.validate(new UpdateRoleRequest(""));

        assertThat(violations).isNotEmpty();
    }
}
