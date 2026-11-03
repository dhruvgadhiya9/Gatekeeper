package com.gatekeeper.qaplatform.run.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.gatekeeper.qaplatform.config.SandboxProperties;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** These settings are security rule 5 in CLAUDE.md. A change here should be deliberate. */
class SandboxContainerSettingsTest {

    private final SandboxProperties properties = new SandboxProperties(
            true, "secret", "runner:1", "proxy:1", "http://backend:8080", "out", 2, 4096, Duration.ofMinutes(60), 1000, List.of());

    @Test
    void runnerIsLockedDownAndLimited() {
        HostConfig config = SandboxContainerSettings.runner(properties, "gk-run-1", true);

        assertThat(config.getReadonlyRootfs()).isTrue();
        assertThat(config.getPrivileged()).isFalse();
        assertThat(config.getCapDrop()).containsExactly(Capability.ALL);
        assertThat(config.getCapAdd()).isNull();
        assertThat(config.getSecurityOpts()).contains("no-new-privileges");
        assertThat(config.getNanoCPUs()).isEqualTo(2_000_000_000L);
        assertThat(config.getMemory()).isEqualTo(4096L * 1024 * 1024);
        assertThat(config.getMemorySwap()).isEqualTo(config.getMemory());
        assertThat(config.getPidsLimit()).isPositive();
        assertThat(config.getAutoRemove()).isTrue();
    }

    @Test
    void nothingFromTheHostIsMounted() {
        for (HostConfig config : List.of(
                SandboxContainerSettings.runner(properties, "gk-run-1", true),
                SandboxContainerSettings.egressProxy("out", true))) {
            assertThat(config.getBinds()).isNullOrEmpty();
            assertThat(config.getMounts()).isNullOrEmpty();
            assertThat(config.getVolumesFrom()).isNullOrEmpty();
            assertThat(config.getPortBindings() == null ? Map.of() : config.getPortBindings().getBindings()).isEmpty();
        }
    }

    @Test
    void runnerOnlyJoinsTheRunsNetwork() {
        assertThat(SandboxContainerSettings.runner(properties, "gk-run-1", true).getNetworkMode()).isEqualTo("gk-run-1");
    }

    @Test
    void containersRunAsNonRootUsers() {
        assertThat(SandboxContainerSettings.RUNNER_USER).doesNotStartWith("0");
        assertThat(SandboxContainerSettings.PROXY_USER).doesNotStartWith("0");
    }

    @Test
    void proxyIsLockedDownToo() {
        HostConfig config = SandboxContainerSettings.egressProxy("out", true);

        assertThat(config.getReadonlyRootfs()).isTrue();
        assertThat(config.getCapDrop()).containsExactly(Capability.ALL);
        assertThat(config.getNetworkMode()).isEqualTo("out");
    }
}
