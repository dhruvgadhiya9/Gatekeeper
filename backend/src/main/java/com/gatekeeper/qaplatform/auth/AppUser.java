package com.gatekeeper.qaplatform.auth;

import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser extends BaseEntity {

    private String issuer;
    private String subject;
    private String email;
    private String displayName;

    @Enumerated(EnumType.STRING)
    private Role role = Role.VIEWER;

    private boolean enabled = true;
    private Instant lastLoginAt;

    protected AppUser() {}

    public AppUser(String issuer, String subject, String email) {
        this.issuer = issuer;
        this.subject = subject;
        this.email = email;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }
}
