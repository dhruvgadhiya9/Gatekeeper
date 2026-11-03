package com.gatekeeper.qaplatform.config;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param apiKey the real Anthropic key, which never leaves the backend
 * @param baseUrl where the proxy forwards requests
 * @param pricing US dollars per million tokens, by model; used for cost estimates only
 */
@Validated
@ConfigurationProperties("app.llm")
public record LlmProperties(
        String apiKey,
        @NotBlank String baseUrl,
        @NotBlank String model,
        @NotBlank String smallModel,
        Map<String, Pricing> pricing) {

    public record Pricing(BigDecimal input, BigDecimal output, BigDecimal cacheWrite, BigDecimal cacheRead) {}

    public LlmProperties {
        pricing = pricing == null ? Map.of() : pricing;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Set<String> allowedModels() {
        return Set.of(model, smallModel);
    }

    @Override
    public String toString() {
        return "LlmProperties[baseUrl=" + baseUrl + ", model=" + model + ", apiKey=***]";
    }
}
