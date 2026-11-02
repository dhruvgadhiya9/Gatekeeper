package com.gatekeeper.qaplatform.project;

import com.gatekeeper.qaplatform.common.crypto.EncryptedStringConverter;
import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "design_source")
public class DesignSource extends BaseEntity {

    public enum Type {
        FIGMA,
        UPLOAD
    }

    private UUID projectId;

    @Enumerated(EnumType.STRING)
    private Type type;

    private String figmaFileKey;

    /** Plain text in memory, ciphertext in the database. Never expose in a DTO. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "encrypted_token")
    private String token;

    protected DesignSource() {}

    public DesignSource(UUID projectId, Type type) {
        this.projectId = projectId;
        this.type = type;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public Type getType() {
        return type;
    }

    public String getFigmaFileKey() {
        return figmaFileKey;
    }

    public void setFigmaFileKey(String figmaFileKey) {
        this.figmaFileKey = figmaFileKey;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
