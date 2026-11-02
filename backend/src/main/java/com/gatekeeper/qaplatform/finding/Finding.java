package com.gatekeeper.qaplatform.finding;

import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "finding")
public class Finding extends BaseEntity {

    public enum Category {
        DESIGN,
        FRONTEND,
        BACKEND,
        SECURITY,
        /** The runner could not tell; triage classifies it. */
        UNKNOWN
    }

    public enum Severity {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW
    }

    public enum Status {
        NEW,
        AWAITING_APPROVAL,
        FILED,
        DUPLICATE,
        NOT_REPRODUCED,
        REJECTED
    }

    private UUID runId;

    @Enumerated(EnumType.STRING)
    private Category category;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    private String title;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> stepsToReproduce = new ArrayList<>();

    private String expected;
    private String actual;
    private String filePath;
    private Integer lineStart;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> evidenceKeys = new ArrayList<>();

    private String fingerprint;
    private boolean confirmed;
    private String zohoIssueId;
    private String assigneeZohoUserId;

    @Enumerated(EnumType.STRING)
    private Status status = Status.NEW;

    protected Finding() {}

    public Finding(UUID runId, Category category, Severity severity, String title) {
        this.runId = runId;
        this.category = category;
        this.severity = severity;
        this.title = title;
    }

    public UUID getRunId() {
        return runId;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public String getTitle() {
        return title;
    }

    public List<String> getStepsToReproduce() {
        return stepsToReproduce;
    }

    public void setStepsToReproduce(List<String> stepsToReproduce) {
        this.stepsToReproduce = stepsToReproduce;
    }

    public String getExpected() {
        return expected;
    }

    public void setExpected(String expected) {
        this.expected = expected;
    }

    public String getActual() {
        return actual;
    }

    public void setActual(String actual) {
        this.actual = actual;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Integer getLineStart() {
        return lineStart;
    }

    public void setLineStart(Integer lineStart) {
        this.lineStart = lineStart;
    }

    public List<String> getEvidenceKeys() {
        return evidenceKeys;
    }

    public void setEvidenceKeys(List<String> evidenceKeys) {
        this.evidenceKeys = evidenceKeys;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public void setConfirmed(boolean confirmed) {
        this.confirmed = confirmed;
    }

    public String getZohoIssueId() {
        return zohoIssueId;
    }

    public void setZohoIssueId(String zohoIssueId) {
        this.zohoIssueId = zohoIssueId;
    }

    public String getAssigneeZohoUserId() {
        return assigneeZohoUserId;
    }

    public void setAssigneeZohoUserId(String assigneeZohoUserId) {
        this.assigneeZohoUserId = assigneeZohoUserId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }
}
