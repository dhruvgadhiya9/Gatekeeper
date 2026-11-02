package com.gatekeeper.qaplatform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gatekeeper.qaplatform.common.error.ConflictException;
import com.gatekeeper.qaplatform.config.AuthProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final AppUserRepository users = mock(AppUserRepository.class);

    @BeforeEach
    void repositoryReturnsWhatItSaves() {
        when(users.save(any())).thenAnswer(call -> call.getArgument(0));
        when(users.findByIssuerAndSubject(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void newUserStartsAsViewer() {
        AppUser user = service(List.of(), List.of()).recordLogin(identity("a@corp.test", true));

        assertThat(user.getRole()).isEqualTo(Role.VIEWER);
        assertThat(user.getLastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void emailIsNormalized() {
        AppUser user = service(List.of(), List.of()).recordLogin(identity(" A@Corp.Test ", true));

        assertThat(user.getEmail()).isEqualTo("a@corp.test");
    }

    @Test
    void verifiedBootstrapEmailBecomesAdmin() {
        AppUser user = service(List.of("boss@corp.test"), List.of()).recordLogin(identity("BOSS@corp.test", true));

        assertThat(user.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void unverifiedBootstrapEmailDoesNotBecomeAdmin() {
        AppUser user = service(List.of("boss@corp.test"), List.of()).recordLogin(identity("boss@corp.test", false));

        assertThat(user.getRole()).isEqualTo(Role.VIEWER);
    }

    @Test
    void existingUserKeepsRoleAndGetsNewEmail() {
        AppUser existing = new AppUser("issuer", "subject", "old@corp.test");
        existing.setRole(Role.QA_LEAD);
        when(users.findByIssuerAndSubject("issuer", "subject")).thenReturn(Optional.of(existing));

        AppUser user = service(List.of(), List.of()).recordLogin(identity("new@corp.test", true));

        assertThat(user.getRole()).isEqualTo(Role.QA_LEAD);
        assertThat(user.getEmail()).isEqualTo("new@corp.test");
    }

    @Test
    void domainOutsideAllowListIsRejected() {
        UserService service = service(List.of(), List.of("corp.test"));

        assertThatThrownBy(() -> service.recordLogin(identity("a@gmail.test", true)))
                .isInstanceOf(LoginRejectedException.class)
                .hasMessage("domain_not_allowed");
    }

    @Test
    void lookalikeDomainIsRejected() {
        UserService service = service(List.of(), List.of("corp.test"));

        assertThatThrownBy(() -> service.recordLogin(identity("a@evilcorp.test", true)))
                .isInstanceOf(LoginRejectedException.class);
    }

    @Test
    void unverifiedEmailIsRejectedWhenDomainsAreRestricted() {
        UserService service = service(List.of(), List.of("corp.test"));

        assertThatThrownBy(() -> service.recordLogin(identity("a@corp.test", false)))
                .isInstanceOf(LoginRejectedException.class);
    }

    @Test
    void allowedDomainIsAccepted() {
        assertThat(service(List.of(), List.of("corp.test")).recordLogin(identity("a@corp.test", true)))
                .isNotNull();
    }

    @Test
    void missingEmailIsRejected() {
        UserService service = service(List.of(), List.of());

        assertThatThrownBy(() -> service.recordLogin(identity(null, true)))
                .isInstanceOf(LoginRejectedException.class)
                .hasMessage("missing_email");
    }

    @Test
    void disabledUserIsRejected() {
        AppUser existing = new AppUser("issuer", "subject", "a@corp.test");
        existing.setEnabled(false);
        when(users.findByIssuerAndSubject("issuer", "subject")).thenReturn(Optional.of(existing));
        UserService service = service(List.of(), List.of());

        assertThatThrownBy(() -> service.recordLogin(identity("a@corp.test", true)))
                .isInstanceOf(LoginRejectedException.class)
                .hasMessage("account_disabled");
    }

    @Test
    void adminCannotChangeOwnAccount() {
        UUID id = UUID.randomUUID();
        AuthenticatedUser actor = new AuthenticatedUser(id, "boss@corp.test", Role.ADMIN);
        UserService service = service(List.of(), List.of());

        assertThatThrownBy(() -> service.update(id, Role.VIEWER, true, actor)).isInstanceOf(ConflictException.class);
        verify(users, org.mockito.Mockito.never()).findById(any());
    }

    private UserService service(List<String> adminEmails, List<String> domains) {
        AuthProperties properties = new AuthProperties(
                "https://app.test", "secret", Duration.ofHours(1), true, adminEmails, domains);
        return new UserService(users, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OidcIdentity identity(String email, boolean verified) {
        return new OidcIdentity("issuer", "subject", email, verified, "A User");
    }
}
