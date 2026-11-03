package com.gatekeeper.qaplatform.run.llm;

import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** One model request made by a run. Holds token counts only, never prompts or responses. */
@Entity
@Table(name = "llm_usage")
public class LlmUsage extends BaseEntity {

    private UUID runId;
    private String model;
    private int upstreamStatus;
    private long inputTokens;
    private long outputTokens;
    private long cacheWriteTokens;
    private long cacheReadTokens;
    private long billableTokens;
    private BigDecimal costUsd;

    protected LlmUsage() {}

    public LlmUsage(UUID runId, String model, int upstreamStatus, TokenCounts tokens, BigDecimal costUsd) {
        this.runId = runId;
        this.model = model;
        this.upstreamStatus = upstreamStatus;
        this.inputTokens = tokens.input();
        this.outputTokens = tokens.output();
        this.cacheWriteTokens = tokens.cacheWrite();
        this.cacheReadTokens = tokens.cacheRead();
        this.billableTokens = tokens.billable();
        this.costUsd = costUsd;
    }

    public String getModel() {
        return model;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public long getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    public long getCacheReadTokens() {
        return cacheReadTokens;
    }

    public long getBillableTokens() {
        return billableTokens;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }
}
