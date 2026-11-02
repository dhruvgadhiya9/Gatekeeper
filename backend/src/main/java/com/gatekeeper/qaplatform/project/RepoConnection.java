package com.gatekeeper.qaplatform.project;

import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "repo_connection")
public class RepoConnection extends BaseEntity {

    public enum Type {
        FRONTEND,
        BACKEND,
        OTHER
    }

    public enum Provider {
        GITHUB
    }

    private UUID projectId;

    @Enumerated(EnumType.STRING)
    private Type type;

    @Enumerated(EnumType.STRING)
    private Provider provider;

    private String repoFullName;
    private String defaultBranch;
    private Long installationId;

    protected RepoConnection() {}

    public RepoConnection(
            UUID projectId, Type type, Provider provider, String repoFullName, String defaultBranch) {
        this.projectId = projectId;
        this.type = type;
        this.provider = provider;
        this.repoFullName = repoFullName;
        this.defaultBranch = defaultBranch;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public Type getType() {
        return type;
    }

    public Provider getProvider() {
        return provider;
    }

    public String getRepoFullName() {
        return repoFullName;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public Long getInstallationId() {
        return installationId;
    }

    public void setInstallationId(Long installationId) {
        this.installationId = installationId;
    }
}
