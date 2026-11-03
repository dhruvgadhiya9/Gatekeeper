package com.gatekeeper.qaplatform.run.sandbox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.qaplatform.IntegrationTestBase;
import com.gatekeeper.qaplatform.config.StorageProperties;
import com.gatekeeper.qaplatform.project.FeatureSpec;
import com.gatekeeper.qaplatform.project.FeatureSpecRepository;
import com.gatekeeper.qaplatform.project.Project;
import com.gatekeeper.qaplatform.project.ProjectRepository;
import com.gatekeeper.qaplatform.project.TestAccount;
import com.gatekeeper.qaplatform.project.TestAccountRepository;
import com.gatekeeper.qaplatform.run.TestRun;
import com.gatekeeper.qaplatform.run.TestRunRepository;
import com.gatekeeper.qaplatform.run.llm.LlmUsageRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * One full run: the real runner image and real Claude Code CLI, a real sandbox and egress proxy, and
 * a real backend and storage. Only the model is simulated, so this spends nothing. The simulated
 * model tells the agent to run a script that records what the sandbox allows.
 *
 * <p>Needs both images built locally, and Docker Desktop for host.docker.internal. See the README.
 */
@Tag("runner-image")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class RunnerImageEndToEndTest extends IntegrationTestBase {

    private static final String REAL_KEY = "sk-ant-real-key-for-this-test";
    private static final String PASSWORD = "e2e-test-password";
    private static final String DOCKER_HOST = "host.docker.internal";
    private static final WireMockServer MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
                    "chainguard/minio@sha256:0a01100085e30fb08d2f39e39c9f5c9d83a95707f220b007d813b690cd41622d")
            .withCommand("server", "/tmp/data")
            .withEnv("MINIO_ROOT_USER", "e2eaccess")
            .withEnv("MINIO_ROOT_PASSWORD", "e2esecretvalue")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    /** Runs inside the sandbox as the agent's one tool call. */
    private static final String PROBE = String.join("; ",
            "cd /workspace",
            "id -u > results/evidence/uid.txt",
            "curl -sS -m 15 -o /dev/null -w 'status=%{http_code}' https://example.com > results/evidence/blocked.txt 2>&1 || echo \" exit=$?\" >> results/evidence/blocked.txt",
            "(touch /opt/marker 2>&1 || true) > results/evidence/readonly.txt",
            "jq -r .stagingUrl job.json > results/evidence/staging.txt",
            "jq -r .testAccounts[0].password job.json > results/evidence/password.txt",
            "(env | grep -c " + REAL_KEY + " || true) > results/evidence/key-in-env.txt",
            "semgrep --version > results/evidence/semgrep.txt 2>&1",
            "gitleaks version > results/evidence/gitleaks.txt 2>&1",
            "osv-scanner --version > results/evidence/osv.txt 2>&1",
            "go version > results/evidence/go.txt 2>&1",
            "mvn -v > results/evidence/maven.txt 2>&1",
            "gradle -v > results/evidence/gradle.txt 2>&1",
            "npx playwright --version > results/evidence/playwright.txt 2>&1",
            "node -e \"require('@axe-core/playwright'); console.log('axe ok')\" > results/evidence/axe.txt 2>&1",
            "ls /usr/local/bin/zap.sh /usr/local/bin/zap-baseline > results/evidence/zap.txt 2>&1",
            "ln -s /etc/passwd results/evidence/symlink.txt",
            "echo '{\"runId\":\"e2e\",\"summary\":{\"featuresTested\":0,\"passed\":0,\"failed\":0,\"notes\":\"probe\"},\"featureResults\":[],\"findings\":[]}' > results/findings.json");

    @Autowired private RunSandboxService sandboxes;
    @Autowired private ProjectRepository projects;
    @Autowired private TestAccountRepository accounts;
    @Autowired private FeatureSpecRepository features;
    @Autowired private TestRunRepository runs;
    @Autowired private LlmUsageRepository usage;
    @Autowired private S3Client s3;
    @Autowired private StorageProperties storage;
    @Autowired private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) throws Exception {
        MODEL.start();
        MINIO.start();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        int backendPort = port;
        int minioPort = MINIO.getMappedPort(9000);
        registry.add("server.port", () -> backendPort);
        registry.add("test.storage.in-memory", () -> "false");
        registry.add("app.sandbox.enabled", () -> "true");
        registry.add("app.sandbox.backend-url", () -> "http://" + DOCKER_HOST + ":" + backendPort);
        registry.add("app.sandbox.outbound-network", () -> "bridge");
        registry.add("app.sandbox.egress-allow-list", () -> "registry.npmjs.org");
        registry.add("app.sandbox.time-limit", () -> "15m");
        registry.add("app.sandbox.memory-mb", () -> "4096");
        registry.add("app.storage.endpoint", () -> "http://localhost:" + minioPort);
        registry.add("app.storage.sandbox-endpoint", () -> "http://" + DOCKER_HOST + ":" + minioPort);
        registry.add("app.storage.access-key", () -> "e2eaccess");
        registry.add("app.storage.secret-key", () -> "e2esecretvalue");
        registry.add("app.storage.create-bucket", () -> "true");
        registry.add("app.llm.api-key", () -> REAL_KEY);
        registry.add("app.llm.base-url", MODEL::baseUrl);
    }

    @AfterAll
    static void stop() {
        MODEL.stop();
        MINIO.stop();
    }

    @Test
    void aRunGoesFromLaunchToUploadedResultsInsideTheSandbox() throws Exception {
        stubModel();
        UUID projectId = projects.save(new Project("E2E", "https://staging.example.test", "qa@example.test")).getId();
        accounts.save(new TestAccount(projectId, "admin", "qa@staging.example.test", PASSWORD, "https://staging.example.test/login"));
        features.save(new FeatureSpec(projectId, "Login", "d", "a", FeatureSpec.Priority.HIGH));
        UUID runId = runs.save(new TestRun(projectId)).getId();

        sandboxes.launch(runId);
        try {
            assertRunSucceeded(runId, waitForCallback(runId));
        } finally {
            sandboxes.stop(runId);
        }
    }

    private void assertRunSucceeded(UUID runId, TestRun run) throws Exception {
        var manifest = objectMapper.readTree(run.getResultsManifest());
        String diagnostics = "manifest=" + run.getResultsManifest() + "\nstderr=" + read(runId, "results/agent-stderr.log");
        assertThat(run.getAgentExitCode()).as(diagnostics).isZero();
        assertThat(manifest.get("outcome").asText()).isEqualTo("COMPLETED");
        assertThat(manifest.get("agentSummary").asText()).contains("num_turns");

        // The agent loop ran, and its tool call really executed inside the sandbox.
        assertThat(read(runId, "results/findings.json")).contains("\"notes\":\"probe\"");
        assertThat(read(runId, "results/evidence/uid.txt").trim()).isEqualTo("10001");
        assertThat(read(runId, "results/evidence/staging.txt").trim()).isEqualTo("https://staging.example.test");
        assertThat(read(runId, "results/evidence/password.txt").trim()).isEqualTo(PASSWORD);

        // The sandbox's limits held.
        assertThat(read(runId, "results/evidence/blocked.txt")).doesNotContain("status=200");
        assertThat(read(runId, "results/evidence/readonly.txt")).containsIgnoringCase("read-only");
        assertThat(read(runId, "results/evidence/key-in-env.txt").trim()).isEqualTo("0");
        assertThat(manifest.get("files").toString()).doesNotContain("symlink");

        // Every tool is really there and runnable.
        assertThat(read(runId, "results/evidence/semgrep.txt")).contains("1.183.0");
        assertThat(read(runId, "results/evidence/gitleaks.txt")).contains("8.30.1");
        assertThat(read(runId, "results/evidence/osv.txt")).contains("2.7.0");
        assertThat(read(runId, "results/evidence/go.txt")).contains("go1.27.3");
        assertThat(read(runId, "results/evidence/maven.txt")).contains("3.9.16");
        assertThat(read(runId, "results/evidence/gradle.txt")).contains("9.8.2");
        assertThat(read(runId, "results/evidence/playwright.txt")).contains("1.64.1");
        assertThat(read(runId, "results/evidence/axe.txt")).contains("axe ok");
        assertThat(read(runId, "results/evidence/zap.txt")).contains("zap.sh", "zap-baseline");

        // The model only ever saw the real key from the backend, and usage was recorded.
        MODEL.verify(postRequestedFor(urlPathEqualTo("/v1/messages")).withHeader("x-api-key", equalTo(REAL_KEY)));
        assertThat(usage.findByRunId(runId)).isNotEmpty();
        assertThat(runs.findById(runId).orElseThrow().getTokenUsage()).isPositive();
    }

    private void stubModel() {
        String toolInput;
        try {
            toolInput = objectMapper.writeValueAsString(Map.of("command", PROBE, "description", "Probe the sandbox"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        MODEL.stubFor(post(urlPathEqualTo("/v1/messages/count_tokens")).willReturn(okJson("{\"input_tokens\":100}")));
        MODEL.stubFor(post(urlPathEqualTo("/v1/messages"))
                .atPriority(5)
                .willReturn(stream(endTurnEvents())));
        MODEL.stubFor(post(urlPathEqualTo("/v1/messages"))
                .atPriority(1)
                // The first turn of the main conversation: it offers tools and has no reply yet.
                .withRequestBody(containing("Begin the test run"))
                .withRequestBody(matchingJsonPath("$.tools[?(@.name == 'Bash')]"))
                .withRequestBody(notMatching("(?s).*\"role\"\\s*:\\s*\"assistant\".*"))
                .willReturn(stream(toolUseEvents(toolInput))));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder stream(String events) {
        return aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream").withBody(events);
    }

    private String toolUseEvents(String toolInput) {
        return event("message_start", start())
                + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_e2e\",\"name\":\"Bash\",\"input\":{}}}")
                + event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":" + quote(toolInput) + "}}")
                + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                + event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":50}}")
                + event("message_stop", "{\"type\":\"message_stop\"}");
    }

    private String endTurnEvents() {
        return event("message_start", start())
                + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                + event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Done.\"}}")
                + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                + event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":5}}")
                + event("message_stop", "{\"type\":\"message_stop\"}");
    }

    private static String start() {
        return "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_e2e\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":100,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0,\"output_tokens\":1}}}";
    }

    private static String event(String name, String data) {
        return "event: " + name + "\ndata: " + data + "\n\n";
    }

    private String quote(String text) {
        try {
            return objectMapper.writeValueAsString(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private TestRun waitForCallback(UUID runId) throws InterruptedException {
        Instant giveUp = Instant.now().plus(Duration.ofMinutes(8));
        while (Instant.now().isBefore(giveUp)) {
            TestRun run = runs.findById(runId).orElseThrow();
            if (run.getSandboxFinishedAt() != null) {
                return run;
            }
            Thread.sleep(3000);
        }
        throw new AssertionError("The sandbox never reported back");
    }

    private String read(UUID runId, String path) {
        try {
            return s3.getObjectAsBytes(request -> request.bucket(storage.bucket()).key("runs/" + runId + "/" + path)).asUtf8String();
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
            return "(" + path + " wasn't uploaded)";
        }
    }
}
