package com.gatekeeper.qaplatform.project;

import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "feature_spec")
public class FeatureSpec extends BaseEntity {

    public enum Priority {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW
    }

    private UUID projectId;
    private String title;
    private String description;
    private String acceptanceCriteria;

    @Enumerated(EnumType.STRING)
    private Priority priority;

    protected FeatureSpec() {}

    public FeatureSpec(
            UUID projectId, String title, String description, String acceptanceCriteria, Priority priority) {
        this.projectId = projectId;
        this.title = title;
        this.description = description;
        this.acceptanceCriteria = acceptanceCriteria;
        this.priority = priority;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getAcceptanceCriteria() {
        return acceptanceCriteria;
    }

    public Priority getPriority() {
        return priority;
    }
}
