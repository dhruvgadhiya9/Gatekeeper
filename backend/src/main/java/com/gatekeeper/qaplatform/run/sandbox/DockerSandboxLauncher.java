package com.gatekeeper.qaplatform.run.sandbox;

import com.gatekeeper.qaplatform.config.SandboxProperties;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Network;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs each sandbox in its own internal Docker network, which has no route anywhere. A proxy
 * container is the one thing on both that network and an outbound one, so everything the sandbox
 * reaches has to pass its allow-list.
 */
public class DockerSandboxLauncher implements SandboxLauncher {

    static final String PROXY_ALIAS = "egress-proxy";
    static final int PROXY_PORT = 3128;
    private static final Logger log = LoggerFactory.getLogger(DockerSandboxLauncher.class);
    private static final String LABEL_SANDBOX = "gatekeeper.sandbox";
    private static final String LABEL_RUN = "gatekeeper.run";
    private static final String LABEL_ROLE = "gatekeeper.role";
    private static final String LABEL_EXPIRES = "gatekeeper.expires";
    private static final String ROLE_RUNNER = "runner";
    private static final String ROLE_PROXY = "proxy";
    /** Long enough that clean-up doesn't remove a proxy whose runner is still being created. */
    private static final Duration PROXY_GRACE = Duration.ofMinutes(2);

    private final DockerClient docker;
    private final SandboxProperties properties;
    private final Clock clock;
    private final boolean autoRemove;

    public DockerSandboxLauncher(DockerClient docker, SandboxProperties properties, Clock clock) {
        this(docker, properties, clock, true);
    }

    /** @param autoRemove false only in tests, which need to read a finished container's logs */
    DockerSandboxLauncher(DockerClient docker, SandboxProperties properties, Clock clock, boolean autoRemove) {
        this.docker = docker;
        this.properties = properties;
        this.clock = clock;
        this.autoRemove = autoRemove;
    }

    @Override
    public void launch(SandboxRequest request) {
        Map<String, String> labels = Map.of(
                LABEL_SANDBOX, "true",
                LABEL_RUN, request.runId().toString(),
                LABEL_EXPIRES, request.expiresAt().toString());
        String networkName = networkName(request.runId());
        try {
            String networkId = docker.createNetworkCmd()
                    .withName(networkName)
                    .withDriver("bridge")
                    .withInternal(true)
                    .withLabels(labels)
                    .exec()
                    .getId();
            startProxy(request, labels, networkId);
            startRunner(request, labels, networkName);
            log.info("Started sandbox for run {}", request.runId());
        } catch (RuntimeException e) {
            stop(request.runId());
            throw e;
        }
    }

    private void startProxy(SandboxRequest request, Map<String, String> labels, String networkId) {
        CreateContainerResponse proxy = docker.createContainerCmd(properties.egressProxyImage())
                .withName("gk-proxy-" + request.runId())
                .withUser(SandboxContainerSettings.PROXY_USER)
                .withLabels(withRole(labels, ROLE_PROXY))
                .withEnv(
                        "ALLOWED_HOSTS=" + String.join(",", request.allowedHosts()),
                        "ALLOWED_PORTS=" + request.allowedTlsPorts().stream().map(String::valueOf).collect(Collectors.joining(",")))
                .withHostConfig(SandboxContainerSettings.egressProxy(properties.outboundNetwork(), autoRemove))
                .exec();
        docker.connectToNetworkCmd()
                .withNetworkId(networkId)
                .withContainerId(proxy.getId())
                .withContainerNetwork(new ContainerNetwork().withAliases(PROXY_ALIAS))
                .exec();
        docker.startContainerCmd(proxy.getId()).exec();
    }

    private void startRunner(SandboxRequest request, Map<String, String> labels, String networkName) {
        String proxyUrl = "http://" + PROXY_ALIAS + ":" + PROXY_PORT;
        List<String> environment = new java.util.ArrayList<>(request.environment().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList());
        environment.addAll(List.of(
                "HTTP_PROXY=" + proxyUrl,
                "HTTPS_PROXY=" + proxyUrl,
                "http_proxy=" + proxyUrl,
                "https_proxy=" + proxyUrl,
                "NO_PROXY=localhost,127.0.0.1",
                "no_proxy=localhost,127.0.0.1"));
        CreateContainerResponse runner = docker.createContainerCmd(properties.runnerImage())
                .withName("gk-runner-" + request.runId())
                .withUser(SandboxContainerSettings.RUNNER_USER)
                .withLabels(withRole(labels, ROLE_RUNNER))
                .withEnv(environment)
                .withHostConfig(SandboxContainerSettings.runner(properties, networkName, autoRemove))
                .exec();
        docker.startContainerCmd(runner.getId()).exec();
    }

    @Override
    public void stop(UUID runId) {
        containers().stream()
                .filter(container -> runId.toString().equals(container.getLabels().get(LABEL_RUN)))
                .forEach(this::remove);
        removeNetwork(networkName(runId));
    }

    @Override
    public void cleanUp() {
        Instant now = clock.instant();
        List<Container> containers = containers();
        Set<String> runnersAlive = containers.stream()
                .filter(container -> ROLE_RUNNER.equals(container.getLabels().get(LABEL_ROLE)))
                .filter(container -> "running".equals(container.getState()) || "created".equals(container.getState()))
                .map(container -> container.getLabels().get(LABEL_RUN))
                .collect(Collectors.toSet());

        Set<String> runs = new HashSet<>();
        for (Container container : containers) {
            String run = container.getLabels().get(LABEL_RUN);
            boolean expired = now.isAfter(Instant.parse(container.getLabels().get(LABEL_EXPIRES)));
            boolean orphaned = !runnersAlive.contains(run)
                    && now.isAfter(Instant.ofEpochSecond(container.getCreated()).plus(PROXY_GRACE));
            if (expired || orphaned) {
                runs.add(run);
            }
        }
        runs.forEach(run -> {
            log.info("Removing sandbox for run {}", run);
            stop(UUID.fromString(run));
        });

        Set<String> remaining = containers().stream()
                .map(container -> container.getLabels().get(LABEL_RUN))
                .collect(Collectors.toSet());
        for (Network network : docker.listNetworksCmd().withFilter("label", List.of(LABEL_SANDBOX + "=true")).exec()) {
            String run = network.getLabels().get(LABEL_RUN);
            Instant expires = Instant.parse(network.getLabels().get(LABEL_EXPIRES));
            if (!remaining.contains(run) && now.isAfter(expires)) {
                removeNetwork(network.getName());
            }
        }
    }

    private List<Container> containers() {
        return docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(LABEL_SANDBOX, "true"))
                .exec();
    }

    /** Auto-removal can get there first, which is fine. */
    private void remove(Container container) {
        try {
            docker.removeContainerCmd(container.getId()).withForce(true).withRemoveVolumes(true).exec();
        } catch (NotFoundException | ConflictException alreadyGoing) {
            log.debug("Container {} was already being removed", container.getId());
        }
    }

    private void removeNetwork(String name) {
        try {
            docker.removeNetworkCmd(name).exec();
        } catch (NotFoundException alreadyGone) {
            log.debug("Network {} was already gone", name);
        } catch (ConflictException stillInUse) {
            log.debug("Network {} is still in use; clean-up will retry", name);
        }
    }

    private static String networkName(UUID runId) {
        return "gk-run-" + runId;
    }

    private static Map<String, String> withRole(Map<String, String> labels, String role) {
        Map<String, String> result = new java.util.HashMap<>(labels);
        result.put(LABEL_ROLE, role);
        return result;
    }
}
