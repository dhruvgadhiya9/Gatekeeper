package com.gatekeeper.qaplatform.auth;

import com.gatekeeper.qaplatform.audit.Audited;
import com.gatekeeper.qaplatform.common.error.ConflictException;
import com.gatekeeper.qaplatform.common.error.ResourceNotFoundException;
import com.gatekeeper.qaplatform.config.AuthProperties;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final AppUserRepository users;
    private final AuthProperties properties;
    private final Clock clock;

    public UserService(AppUserRepository users, AuthProperties properties, Clock clock) {
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Creates or updates the user for a successful OIDC login. New users start as VIEWER.
     *
     * <p>Emails decide access only when the provider says they are verified. Otherwise an account
     * at a permissive provider could claim an administrator's address.
     *
     * @throws LoginRejectedException if this user may not sign in
     */
    @Transactional
    public AppUser recordLogin(OidcIdentity identity) {
        String email = identity.email() == null ? null : identity.email().trim().toLowerCase(Locale.ROOT);
        if (email == null || email.isEmpty()) {
            throw new LoginRejectedException("missing_email");
        }
        if (!isDomainAllowed(email, identity.emailVerified())) {
            throw new LoginRejectedException("domain_not_allowed");
        }

        AppUser user = users.findByIssuerAndSubject(identity.issuer(), identity.subject())
                .orElseGet(() -> new AppUser(identity.issuer(), identity.subject(), email));
        if (!user.isEnabled()) {
            throw new LoginRejectedException("account_disabled");
        }

        user.setEmail(email);
        user.setDisplayName(identity.displayName());
        user.setLastLoginAt(clock.instant());
        if (identity.emailVerified() && properties.adminEmails().contains(email)) {
            user.setRole(Role.ADMIN);
        }
        return users.save(user);
    }

    @Transactional(readOnly = true)
    public AppUser get(UUID id) {
        return users.findById(id).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    @Transactional(readOnly = true)
    public List<AppUser> list() {
        return users.findAllByOrderByEmailAsc();
    }

    /** Admins cannot change their own account, so the last admin can never lock everyone out. */
    @Audited(action = "USER_UPDATE", target = "#id", details = "{role: #role, enabled: #enabled}")
    @Transactional
    public AppUser update(UUID id, Role role, boolean enabled, AuthenticatedUser actor) {
        if (id.equals(actor.id())) {
            throw new ConflictException("You cannot change your own role or status.");
        }
        AppUser user = get(id);
        user.setRole(role);
        user.setEnabled(enabled);
        return user;
    }

    private boolean isDomainAllowed(String email, boolean emailVerified) {
        if (properties.allowedEmailDomains().isEmpty()) {
            return true;
        }
        String domain = email.substring(email.lastIndexOf('@') + 1);
        return emailVerified && properties.allowedEmailDomains().contains(domain);
    }
}
