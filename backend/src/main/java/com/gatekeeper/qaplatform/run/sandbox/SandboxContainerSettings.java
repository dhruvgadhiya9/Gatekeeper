package com.gatekeeper.qaplatform.run.sandbox;

import com.gatekeeper.qaplatform.config.SandboxProperties;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import java.util.Map;

/**
 * The isolation settings for sandbox containers, in one place so they can be tested. Nothing here
 * mounts the Docker socket or any host path, and that must stay true.
 */
final class SandboxContainerSettings {

    static final String RUNNER_USER = "10001:10001";
    static final String PROXY_USER = "65534:65534";
    private static final long NANOS_PER_CPU = 1_000_000_000L;
    private static final long BYTES_PER_MB = 1024L * 1024;
    private static final long RUNNER_PIDS = 4096L;
    private static final long PROXY_PIDS = 256L;
    private static final long PROXY_MEMORY_MB = 256L;
    private static final long SHARED_MEMORY_MB = 1024L;

    private SandboxContainerSettings() {}

    /** Its only network is the run's internal one, whose only way out is the egress proxy. */
    static HostConfig runner(SandboxProperties properties, String runNetwork, boolean autoRemove) {
        long memory = properties.memoryMb() * BYTES_PER_MB;
        return locked(new HostConfig())
                .withNetworkMode(runNetwork)
                .withNanoCPUs((long) (properties.cpus() * NANOS_PER_CPU))
                .withMemory(memory)
                .withMemorySwap(memory)
                .withPidsLimit(RUNNER_PIDS)
                .withShmSize(SHARED_MEMORY_MB * BYTES_PER_MB)
                .withTmpFs(Map.of("/tmp", "rw,nosuid,nodev,size=2g,mode=1777"))
                .withAutoRemove(autoRemove);
    }

    static HostConfig egressProxy(String outboundNetwork, boolean autoRemove) {
        long memory = PROXY_MEMORY_MB * BYTES_PER_MB;
        return locked(new HostConfig())
                .withNetworkMode(outboundNetwork)
                .withNanoCPUs(NANOS_PER_CPU / 2)
                .withMemory(memory)
                .withMemorySwap(memory)
                .withPidsLimit(PROXY_PIDS)
                .withTmpFs(Map.of("/tmp", "rw,nosuid,nodev,size=16m,mode=1777"))
                .withAutoRemove(autoRemove);
    }

    private static HostConfig locked(HostConfig config) {
        return config
                .withPrivileged(false)
                .withReadonlyRootfs(true)
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(java.util.List.of("no-new-privileges"))
                .withInit(true);
    }
}
