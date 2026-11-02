package com.gatekeeper.qaplatform.run;

import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "run_step")
public class RunStep extends BaseEntity {

    public enum Agent {
        DESIGN,
        FRONTEND,
        BACKEND,
        API,
        SECURITY,
        ACCESSIBILITY,
        PERFORMANCE
    }

    public enum Status {
        PENDING,
        RUNNING,
        PASSED,
        FAILED,
        SKIPPED
    }

    private UUID runId;

    @Enumerated(EnumType.STRING)
    private Agent agent;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    private String log;

    protected RunStep() {}

    public RunStep(UUID runId, Agent agent) {
        this.runId = runId;
        this.agent = agent;
    }

    public UUID getRunId() {
        return runId;
    }

    public Agent getAgent() {
        return agent;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getLog() {
        return log;
    }

    public void setLog(String log) {
        this.log = log;
    }
}
