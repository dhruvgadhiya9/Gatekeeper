package com.gatekeeper.qaplatform.run.llm;

import com.gatekeeper.qaplatform.config.LlmProperties;
import com.gatekeeper.qaplatform.config.LlmProperties.Pricing;
import com.gatekeeper.qaplatform.run.TestRunRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Records what each model request used. Costs are estimates from the configured prices. */
@Component
public class LlmUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageRecorder.class);
    private static final BigDecimal PER_MILLION = BigDecimal.valueOf(1_000_000);

    private final LlmUsageRepository usage;
    private final TestRunRepository runs;
    private final LlmProperties properties;

    public LlmUsageRecorder(LlmUsageRepository usage, TestRunRepository runs, LlmProperties properties) {
        this.usage = usage;
        this.runs = runs;
        this.properties = properties;
    }

    public void record(UUID runId, String model, int status, TokenCounts tokens) {
        BigDecimal cost = cost(model, tokens);
        usage.save(new LlmUsage(runId, model, status, tokens, cost));
        runs.addUsage(runId, tokens.billable(), cost);
        log.info(
                "LLM usage run={} model={} status={} input={} output={} cacheWrite={} cacheRead={} costUsd={}",
                runId, model, status, tokens.input(), tokens.output(), tokens.cacheWrite(), tokens.cacheRead(), cost);
    }

    private BigDecimal cost(String model, TokenCounts tokens) {
        Pricing pricing = properties.pricing().get(model);
        if (pricing == null) {
            log.warn("No price configured for model {}, so its cost is recorded as zero", model);
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(tokens.input()).multiply(pricing.input())
                .add(BigDecimal.valueOf(tokens.output()).multiply(pricing.output()))
                .add(BigDecimal.valueOf(tokens.cacheWrite()).multiply(pricing.cacheWrite()))
                .add(BigDecimal.valueOf(tokens.cacheRead()).multiply(pricing.cacheRead()))
                .divide(PER_MILLION, 6, RoundingMode.HALF_UP);
    }
}
