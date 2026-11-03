package com.gatekeeper.qaplatform.run;

import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "test_run")
public class TestRun extends BaseEntity {

    public enum Status {
        QUEUED,
        PLANNING,
        AWAITING_APPROVAL,
        RUNNING,
        TRIAGING,
        REPORTING,
        DONE,
        FAILED,
        CANCELLED
    }

    private UUID projectId;

    @Enumerated(EnumType.STRING)
    private Status status = Status.QUEUED;

    private Instant startedAt;
    private Instant finishedAt;
    private long tokenUsage;
    private BigDecimal costUsd = BigDecimal.ZERO;
    private long tokenBudget;
    private Instant deadlineAt;
    private Instant sandboxFinishedAt;
    private Integer agentExitCode;

    @JdbcTypeCode(SqlTypes.JSON)
    private String resultsManifest;

    protected TestRun() {}

    public TestRun(UUID projectId) {
        this.projectId = projectId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public long getTokenUsage() {
        return tokenUsage;
    }

    public void setTokenUsage(long tokenUsage) {
        this.tokenUsage = tokenUsage;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public long getTokenBudget() {
        return tokenBudget;
    }

    public void setTokenBudget(long tokenBudget) {
        this.tokenBudget = tokenBudget;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public void setDeadlineAt(Instant deadlineAt) {
        this.deadlineAt = deadlineAt;
    }

    public Instant getSandboxFinishedAt() {
        return sandboxFinishedAt;
    }

    public Integer getAgentExitCode() {
        return agentExitCode;
    }

    public String getResultsManifest() {
        return resultsManifest;
    }

    /** Ends the sandbox's access: its job token stops working from here on. */
    public void finishSandbox(Instant at, Integer exitCode, String manifestJson) {
        this.sandboxFinishedAt = at;
        this.agentExitCode = exitCode;
        this.resultsManifest = manifestJson;
    }

    public boolean acceptsRunner(Instant now) {
        boolean ended = status == Status.DONE || status == Status.FAILED || status == Status.CANCELLED;
        boolean expired = deadlineAt != null && now.isAfter(deadlineAt);
        return !ended && !expired && sandboxFinishedAt == null;
    }

    public void setCostUsd(BigDecimal costUsd) {
        this.costUsd = costUsd;
    }
}
