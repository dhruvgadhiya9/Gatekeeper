package com.gatekeeper.qaplatform.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param jobTokenSecret signs tokens for sandboxes; separate from the login token key
 * @param egressAllowList hosts every sandbox may reach; each run adds its staging site, the backend
 *     and storage
 */
@Validated
@ConfigurationProperties("app.sandbox")
public record SandboxProperties(
        boolean enabled,
        @NotBlank String jobTokenSecret,
        @NotBlank String runnerImage,
        @NotBlank String egressProxyImage,
        @NotBlank String backendUrl,
        @NotBlank String outboundNetwork,
        @Positive double cpus,
        @Positive long memoryMb,
        @NotNull Duration timeLimit,
        @Positive long tokenBudget,
        List<String> egressAllowList) {

    public SandboxProperties {
        egressAllowList = egressAllowList == null
                ? List.of()
                : egressAllowList.stream().map(String::trim).filter(host -> !host.isEmpty()).toList();
    }

    @Override
    public String toString() {
        return "SandboxProperties[enabled=" + enabled + ", runnerImage=" + runnerImage + ", jobTokenSecret=***]";
    }
}
