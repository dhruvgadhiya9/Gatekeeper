package com.gatekeeper.qaplatform.run;

import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "test_plan")
public class TestPlan extends BaseEntity {

    public enum Status {
        DRAFT,
        APPROVED,
        REJECTED
    }

    private UUID runId;
    private String markdown;

    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT;

    private String approvedBy;

    protected TestPlan() {}

    public TestPlan(UUID runId, String markdown) {
        this.runId = runId;
        this.markdown = markdown;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getMarkdown() {
        return markdown;
    }

    public void setMarkdown(String markdown) {
        this.markdown = markdown;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }
}
