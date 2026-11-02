package com.gatekeeper.qaplatform.routing;

import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import com.gatekeeper.qaplatform.finding.Finding;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "department_mapping")
public class DepartmentMapping extends BaseEntity {

    private UUID projectId;

    @Enumerated(EnumType.STRING)
    private Finding.Category category;

    private String defaultZohoUserId;
    private String fallbackZohoUserId;

    /** Added as a follower on HIGH and CRITICAL security findings. */
    private String securityLeadZohoUserId;

    protected DepartmentMapping() {}

    public DepartmentMapping(
            UUID projectId, Finding.Category category, String defaultZohoUserId, String fallbackZohoUserId) {
        this.projectId = projectId;
        this.category = category;
        this.defaultZohoUserId = defaultZohoUserId;
        this.fallbackZohoUserId = fallbackZohoUserId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public Finding.Category getCategory() {
        return category;
    }

    public String getDefaultZohoUserId() {
        return defaultZohoUserId;
    }

    public void setDefaultZohoUserId(String defaultZohoUserId) {
        this.defaultZohoUserId = defaultZohoUserId;
    }

    public String getFallbackZohoUserId() {
        return fallbackZohoUserId;
    }

    public void setFallbackZohoUserId(String fallbackZohoUserId) {
        this.fallbackZohoUserId = fallbackZohoUserId;
    }

    public String getSecurityLeadZohoUserId() {
        return securityLeadZohoUserId;
    }

    public void setSecurityLeadZohoUserId(String securityLeadZohoUserId) {
        this.securityLeadZohoUserId = securityLeadZohoUserId;
    }
}
