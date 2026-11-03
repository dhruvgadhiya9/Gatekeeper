package com.gatekeeper.qaplatform.run.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.gatekeeper.qaplatform.config.SandboxProperties;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proves the sandbox's isolation on real Docker rather than trusting configuration. Needs the image
 * from runner/egress-proxy, built as gatekeeper-egress-proxy:local. See the README for how to run it.
 *
 * <p>The stand-in runner image prints what it can and can't do, and the test reads that.
 */
@Tag("docker-sandbox")
class DockerSandboxIsolationTest {

    private static final String PROXY_IMAGE = "gatekeeper-egress-proxy:local";
    private static final String BUSYBOX = "busybox:1.37.0";
    private static final String OUTBOUND = "gk-test-outbound-" + UUID.randomUUID();
    private static final String ALLOWED = "gk-test-allowed-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String BLOCKED = "gk-test-blocked-" + UUID.randomUUID().toString().substring(0, 8);

    private static DockerClient docker;

    @BeforeAll
    static void startTargets() throws Exception {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var http = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        docker = DockerClientImpl.getInstance(config, http);
        docker.pullImageCmd(BUSYBOX).start().awaitCompletion(120, TimeUnit.SECONDS);
        docker.createNetworkCmd().withName(OUTBOUND).withDriver("bridge").exec();
        for (String target : List.of(ALLOWED, BLOCKED)) {
            String id = docker.createContainerCmd(BUSYBOX)
                    .withName(target)
                    .withCmd("sh", "-c", "mkdir -p /www && echo reached-" + target + " > /www/index.html && httpd -f -p 8080 -h /www")
                    .withHostConfig(new com.github.dockerjava.api.model.HostConfig().withNetworkMode(OUTBOUND))
                    .exec()
                    .getId();
            docker.startContainerCmd(id).exec();
        }
    }

    @AfterAll
    static void removeTargets() {
        for (String target : List.of(ALLOWED, BLOCKED)) {
            docker.removeContainerCmd(target).withForce(true).exec();
        }
        docker.removeNetworkCmd(OUTBOUND).exec();
    }

    @Test
    void sandboxOnlyReachesAllowedHostsAndCantChangeItsOwnFilesystem() throws Exception {
        String logs = runInSandbox(String.join("; ",
                "echo uid=$(id -u)",
                "sleep 2",
                "echo proxied-allowed=$(wget -q -T 5 -O - -Y on http://" + ALLOWED + ":8080/ 2>&1)",
                "echo proxied-blocked=$(wget -q -T 5 -O - -Y on http://" + BLOCKED + ":8080/ 2>&1)",
                "echo proxied-lookalike=$(wget -q -T 5 -O - -Y on http://x" + ALLOWED + ".invalid:8080/ 2>&1)",
                "echo direct-allowed=$(wget -q -T 5 -O - -Y off http://" + ALLOWED + ":8080/ 2>&1)",
                "echo direct-internet=$(wget -q -T 5 -O - -Y off http://1.1.1.1/ 2>&1)",
                "echo write-root=$(touch /marker 2>&1 && echo written)",
                "echo write-tmp=$(touch /tmp/marker 2>&1 && echo written)",
                "echo socket=$(ls /var/run/docker.sock 2>&1)",
                "echo done"));

        assertThat(logs).contains("uid=10001");
        assertThat(logs).contains("proxied-allowed=reached-" + ALLOWED);
        assertThat(lineStarting(logs, "proxied-blocked=")).doesNotContain("reached-");
        assertThat(lineStarting(logs, "proxied-lookalike=")).doesNotContain("reached-");
        assertThat(lineStarting(logs, "direct-allowed=")).doesNotContain("reached-");
        assertThat(lineStarting(logs, "direct-internet=")).isNotEqualTo("direct-internet=").containsAnyOf("bad address", "timed out", "unreachable", "can't connect");
        assertThat(lineStarting(logs, "write-root=")).contains("Read-only").doesNotContain("written");
        assertThat(lineStarting(logs, "write-tmp=")).contains("written");
        assertThat(lineStarting(logs, "socket=")).contains("No such file");
    }

    @Test
    void stopRemovesEverything() throws Exception {
        UUID runId = UUID.randomUUID();
        DockerSandboxLauncher launcher = launcher(BUSYBOX, false, Clock.systemUTC());
        launcher.launch(request(runId, "sleep 300", Instant.now().plus(Duration.ofHours(1))));
        assertThat(sandboxContainers(runId)).hasSize(2);

        launcher.stop(runId);

        assertThat(sandboxContainers(runId)).isEmpty();
        assertThat(docker.listNetworksCmd().withNameFilter("gk-run-" + runId).exec()).isEmpty();
    }

    @Test
    void cleanUpRemovesSandboxesPastTheirDeadlineAndKeepsOthers() throws Exception {
        UUID expired = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        DockerSandboxLauncher launcher = launcher(BUSYBOX, false, Clock.systemUTC());
        launcher.launch(request(expired, "sleep 300", Instant.now().minusSeconds(1)));
        launcher.launch(request(current, "sleep 300", Instant.now().plus(Duration.ofHours(1))));

        launcher.cleanUp();

        assertThat(sandboxContainers(expired)).isEmpty();
        assertThat(sandboxContainers(current)).hasSize(2);
        launcher.stop(current);
    }

    @Test
    void aFailedLaunchLeavesNothingBehind() {
        UUID runId = UUID.randomUUID();
        DockerSandboxLauncher launcher = launcher("gatekeeper-image-that-does-not-exist:1", false, Clock.systemUTC());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        launcher.launch(request(runId, "true", Instant.now().plus(Duration.ofHours(1)))))
                .isInstanceOf(RuntimeException.class);

        assertThat(sandboxContainers(runId)).isEmpty();
        assertThat(docker.listNetworksCmd().withNameFilter("gk-run-" + runId).exec()).isEmpty();
    }

    private String runInSandbox(String script) throws Exception {
        UUID runId = UUID.randomUUID();
        DockerSandboxLauncher launcher = launcher(BUSYBOX, false, Clock.systemUTC());
        try {
            launcher.launch(request(runId, script, Instant.now().plus(Duration.ofHours(1))));
            String runner = "gk-runner-" + runId;
            docker.waitContainerCmd(runner).exec(new WaitContainerResultCallback()).awaitStatusCode(120, TimeUnit.SECONDS);
            StringBuilder output = new StringBuilder();
            docker.logContainerCmd(runner)
                    .withStdOut(true)
                    .withStdErr(true)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            output.append(new String(frame.getPayload()));
                        }
                    })
                    .awaitCompletion(30, TimeUnit.SECONDS);
            return output.toString();
        } finally {
            launcher.stop(runId);
        }
    }

    private static String lineStarting(String logs, String prefix) {
        return logs.lines().filter(line -> line.startsWith(prefix)).findFirst().orElse("");
    }

    private static List<?> sandboxContainers(UUID runId) {
        return docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of("gatekeeper.run", runId.toString()))
                .exec();
    }

    private static SandboxRequest request(UUID runId, String script, Instant expiresAt) {
        return new SandboxRequest(runId, Map.of("SCRIPT", script), List.of(ALLOWED), List.of(443), expiresAt);
    }

    /** The stand-in runner image runs $SCRIPT, by way of a small wrapper set up below. */
    private static DockerSandboxLauncher launcher(String runnerImage, boolean autoRemove, Clock clock) {
        SandboxProperties properties = new SandboxProperties(
                true, "unused", ensureScriptImage(runnerImage), PROXY_IMAGE, "http://backend:8080", OUTBOUND, 1, 256, Duration.ofMinutes(5), 1, List.of());
        return new DockerSandboxLauncher(docker, properties, clock, autoRemove);
    }

    /** BusyBox has no entrypoint, so a tiny image is built that executes $SCRIPT. */
    private static String ensureScriptImage(String base) {
        if (!BUSYBOX.equals(base)) {
            return base;
        }
        String tag = "gk-test-script-runner:local";
        try {
            Process build = new ProcessBuilder("docker", "build", "-q", "-t", tag, "-")
                    .redirectErrorStream(true)
                    .start();
            try (var stdin = build.getOutputStream()) {
                stdin.write(("FROM " + BUSYBOX + "\nENTRYPOINT [\"sh\", \"-c\", \"eval \\\"$SCRIPT\\\"\"]\n").getBytes());
            }
            String output = new String(build.getInputStream().readAllBytes());
            if (build.waitFor() != 0) {
                throw new IllegalStateException("Could not build the test image: " + output);
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return tag;
    }
}
