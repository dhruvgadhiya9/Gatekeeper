package com.gatekeeper.qaplatform.routing;

import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/** Records a fingerprint before Zoho is called, so a repeated attempt can't create a second issue. */
@Entity
@Table(name = "zoho_issue_link")
public class ZohoIssueLink extends BaseEntity {

    public enum Status {
        /** A call to Zoho is in progress, or the process stopped during one. */
        PENDING,
        /** A call failed in a way that doesn't show whether Zoho created the issue. */
        UNCERTAIN,
        CREATED
    }

    private UUID projectId;
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    private String zohoIssueId;

    protected ZohoIssueLink() {}

    public ZohoIssueLink(UUID projectId, String fingerprint) {
        this.projectId = projectId;
        this.fingerprint = fingerprint;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getZohoIssueId() {
        return zohoIssueId;
    }

    public void markCreated(String issueId) {
        this.zohoIssueId = issueId;
        this.status = Status.CREATED;
    }
}
