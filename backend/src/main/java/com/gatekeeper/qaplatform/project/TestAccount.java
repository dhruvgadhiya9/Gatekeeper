package com.gatekeeper.qaplatform.project;

import com.gatekeeper.qaplatform.common.crypto.EncryptedStringConverter;
import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "test_account")
public class TestAccount extends BaseEntity {

    private UUID projectId;
    private String role;
    private String username;

    /** Plain text in memory, ciphertext in the database. Never expose in a DTO. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "encrypted_password")
    private String password;

    private String loginUrl;

    protected TestAccount() {}

    public TestAccount(UUID projectId, String role, String username, String password, String loginUrl) {
        this.projectId = projectId;
        this.role = role;
        this.username = username;
        this.password = password;
        this.loginUrl = loginUrl;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getRole() {
        return role;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getLoginUrl() {
        return loginUrl;
    }
}
