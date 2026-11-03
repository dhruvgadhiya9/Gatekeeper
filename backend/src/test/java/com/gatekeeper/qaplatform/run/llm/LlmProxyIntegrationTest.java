package com.gatekeeper.qaplatform.run.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gatekeeper.qaplatform.IntegrationTestBase;
import com.gatekeeper.qaplatform.project.Project;
import com.gatekeeper.qaplatform.project.ProjectRepository;
import com.gatekeeper.qaplatform.run.TestRun;
import com.gatekeeper.qaplatform.run.TestRunRepository;
import com.gatekeeper.qaplatform.run.runner.JobTokenService;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@ExtendWith(OutputCaptureExtension.class)
class LlmProxyIntegrationTest extends IntegrationTestBase {

    private static final WireMockServer ANTHROPIC = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final String REAL_KEY = "sk-ant-real-key-value";
    private static final String MESSAGES = "/internal/runner/llm/v1/messages";
    private static final String BODY =
            "{\"model\":\"claude-opus-5-5\",\"max_tokens\":100,\"future_field\":{\"a\":1},\"messages\":[{\"role\":\"user\",\"content\":\"secret prompt text\"}]}";
    private static final String STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":1000,"cache_creation_input_tokens":0,"cache_read_input_tokens":2000000,"output_tokens":1}}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":500}}

            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private JobTokenService jobTokens;
    @Autowired private ProjectRepository projects;
    @Autowired private TestRunRepository runs;
    @Autowired private LlmUsageRepository usage;
    @Autowired private CircuitBreakerRegistry circuitBreakers;

    private UUID runId;
    private String token;

    @DynamicPropertySource
    static void anthropic(DynamicPropertyRegistry registry) {
        ANTHROPIC.start();
        registry.add("app.llm.api-key", () -> REAL_KEY);
        registry.add("app.llm.base-url", ANTHROPIC::baseUrl);
    }

    @AfterAll
    static void stop() {
        ANTHROPIC.stop();
    }

    @BeforeEach
    void setUp() {
        ANTHROPIC.resetAll();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        runId = newRun(1_000_000);
        token = jobTokens.issue(runId, Instant.now().plus(1, ChronoUnit.HOURS));
    }

    @Test
    void replacesTheJobTokenWithTheRealKeyAndForwardsTheRequestUnchanged() throws Exception {
        ANTHROPIC.stubFor(post(urlEqualTo("/v1/messages?beta=true")).willReturn(okJson("{\"usage\":{\"input_tokens\":1}}")));

        mockMvc.perform(MockMvcRequestBuilders.post(MESSAGES + "?beta=true&other=dropped")
                        .header("x-api-key", token)
                        .header("anthropic-version", "2023-06-01")
                        .header("anthropic-beta", "future-beta-2099-01-01")
                        .header("x-unknown", "not-forwarded")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());

        ANTHROPIC.verify(postRequestedFor(urlEqualTo("/v1/messages?beta=true"))
                .withHeader("x-api-key", equalTo(REAL_KEY))
                .withHeader("Authorization", absent())
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .withHeader("anthropic-beta", equalTo("future-beta-2099-01-01"))
                .withHeader("x-unknown", absent())
                .withRequestBody(equalToJson(BODY)));
    }

    @Test
    void streamsTheResponseUnchangedAndRecordsUsageAndCost() throws Exception {
        ANTHROPIC.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withHeader("request-id", "req_1")
                .withBody(STREAM)));

        send(BODY)
                .andExpect(status().isOk())
                .andExpect(header().string("request-id", "req_1"))
                .andExpect(content().string(STREAM));

        LlmUsage recorded = usage.findByRunId(runId).getFirst();
        assertThat(recorded.getInputTokens()).isEqualTo(1000);
        assertThat(recorded.getOutputTokens()).isEqualTo(500);
        assertThat(recorded.getCacheReadTokens()).isEqualTo(2_000_000);
        assertThat(recorded.getCostUsd()).isEqualByComparingTo(new BigDecimal("0.414"));
        TestRun run = runs.findById(runId).orElseThrow();
        assertThat(run.getTokenUsage()).isEqualTo(1000 + 500 + 200_000);
        assertThat(run.getCostUsd()).isEqualByComparingTo(new BigDecimal("0.414"));
    }

    @Test
    void nonStreamingResponsesAreCountedToo() throws Exception {
        ANTHROPIC.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(okJson("{\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}")));

        send(BODY).andExpect(status().isOk());

        assertThat(runs.findById(runId).orElseThrow().getTokenUsage()).isEqualTo(10);
    }

    @Test
    void aRunOverBudgetIsStoppedWithoutCallingAnthropic() throws Exception {
        runId = newRun(10);
        token = jobTokens.issue(runId, Instant.now().plus(1, ChronoUnit.HOURS));
        runs.addUsage(runId, 10, BigDecimal.ZERO);

        send(BODY)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("retry-after", "3600"))
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.error.message").value("This run has used its token budget."));

        ANTHROPIC.verify(0, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void modelsOutsideTheAllowedListAreRefused() throws Exception {
        send("{\"model\":\"claude-fable-5-1\",\"messages\":[]}").andExpect(status().isBadRequest());
        send("{\"messages\":[]}").andExpect(status().isBadRequest());
        send("not json").andExpect(status().isBadRequest());

        ANTHROPIC.verify(0, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void upstreamErrorsPassThroughSoClaudeCodeCanReactToThem() throws Exception {
        String overloaded = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";
        ANTHROPIC.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
                .withStatus(529)
                .withHeader("Content-Type", "application/json")
                .withHeader("retry-after", "5")
                .withBody(overloaded)));

        send(BODY)
                .andExpect(status().is(529))
                .andExpect(header().string("retry-after", "5"))
                .andExpect(content().string(overloaded));
    }

    @Test
    void countTokensForwardsButIsNotBilledOrBudgeted() throws Exception {
        runId = newRun(1);
        token = jobTokens.issue(runId, Instant.now().plus(1, ChronoUnit.HOURS));
        runs.addUsage(runId, 5, BigDecimal.ZERO);
        ANTHROPIC.stubFor(post(urlEqualTo("/v1/messages/count_tokens")).willReturn(okJson("{\"input_tokens\":42}")));

        mockMvc.perform(MockMvcRequestBuilders.post(MESSAGES + "/count_tokens")
                        .header("x-api-key", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.input_tokens").value(42));

        assertThat(usage.findByRunId(runId)).isEmpty();
    }

    @Test
    void onlyTheTwoMessagesPathsAreForwarded() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/internal/runner/llm/v1/files")
                        .header("x-api-key", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void noJobTokenNoAccess() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post(MESSAGES).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.post(MESSAGES)
                        .header("x-api-key", REAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        ANTHROPIC.verify(0, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void anUnreachableUpstreamGivesARetryableError() throws Exception {
        ANTHROPIC.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
                .withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        send(BODY)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("retry-after", "30"))
                .andExpect(jsonPath("$.error.type").value("api_error"));
    }

    @Test
    void theKeyAndPromptsNeverReachTheLogs(CapturedOutput output) throws Exception {
        ANTHROPIC.stubFor(post(urlEqualTo("/v1/messages")).willReturn(okJson("{\"usage\":{\"input_tokens\":1}}")));

        send(BODY).andExpect(status().isOk());

        assertThat(output.getAll()).contains("LLM usage run=" + runId).doesNotContain(REAL_KEY, "secret prompt text", token);
    }

    private ResultActions send(String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(MESSAGES)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private UUID newRun(long budget) {
        UUID projectId = projects.save(new Project("Demo", "https://app.test", "qa@example.test")).getId();
        TestRun run = new TestRun(projectId);
        run.setTokenBudget(budget);
        return runs.save(run).getId();
    }
}
