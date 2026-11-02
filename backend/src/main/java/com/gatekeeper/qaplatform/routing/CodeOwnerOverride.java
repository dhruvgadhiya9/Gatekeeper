package com.gatekeeper.qaplatform.routing;

import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "code_owner_override")
public class CodeOwnerOverride extends BaseEntity {

    private UUID projectId;
    private String pathPattern;
    private String zohoUserId;

    protected CodeOwnerOverride() {}

    public CodeOwnerOverride(UUID projectId, String pathPattern, String zohoUserId) {
        this.projectId = projectId;
        this.pathPattern = pathPattern;
        this.zohoUserId = zohoUserId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getPathPattern() {
        return pathPattern;
    }

    public String getZohoUserId() {
        return zohoUserId;
    }

    public void setZohoUserId(String zohoUserId) {
        this.zohoUserId = zohoUserId;
    }
}
