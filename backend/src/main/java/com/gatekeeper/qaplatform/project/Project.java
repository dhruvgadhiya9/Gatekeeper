package com.gatekeeper.qaplatform.project;

import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "project")
public class Project extends BaseEntity {

    private String name;
    private String zohoPortalId;
    private String zohoProjectId;
    private String stagingUrl;
    private String createdBy;

    protected Project() {}

    public Project(String name, String stagingUrl, String createdBy) {
        this.name = name;
        this.stagingUrl = stagingUrl;
        this.createdBy = createdBy;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getZohoPortalId() {
        return zohoPortalId;
    }

    public void setZohoPortalId(String zohoPortalId) {
        this.zohoPortalId = zohoPortalId;
    }

    public String getZohoProjectId() {
        return zohoProjectId;
    }

    public void setZohoProjectId(String zohoProjectId) {
        this.zohoProjectId = zohoProjectId;
    }

    public String getStagingUrl() {
        return stagingUrl;
    }

    public void setStagingUrl(String stagingUrl) {
        this.stagingUrl = stagingUrl;
    }

    public String getCreatedBy() {
        return createdBy;
    }
}
