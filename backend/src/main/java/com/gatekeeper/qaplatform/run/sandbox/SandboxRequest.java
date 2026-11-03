package com.gatekeeper.qaplatform.run.sandbox;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @param environment variables for the sandbox, including its job token
 * @param allowedHosts the only hosts, with their subdomains, the sandbox can reach
 * @param allowedTlsPorts the ports HTTPS is allowed to
 * @param expiresAt the sandbox is removed after this, whatever it's doing
 */
public record SandboxRequest(
        UUID runId,
        Map<String, String> environment,
        List<String> allowedHosts,
        List<Integer> allowedTlsPorts,
        Instant expiresAt) {

    @Override
    public String toString() {
        return "SandboxRequest[runId=" + runId + ", allowedHosts=" + allowedHosts + ", environment=***]";
    }
}
