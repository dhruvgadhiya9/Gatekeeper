package com.gatekeeper.qaplatform.run.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.qaplatform.config.LlmProperties;
import com.gatekeeper.qaplatform.run.TestRun;
import com.gatekeeper.qaplatform.run.TestRunRepository;
import com.gatekeeper.qaplatform.run.runner.RunnerPrincipal;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The only way a sandbox reaches a model. It swaps the job token for the real key, enforces the
 * run's token budget, and records usage. Bodies pass through unchanged and are never logged.
 */
@RestController
@RequestMapping("/internal/runner/llm/v1")
public class LlmProxyController {

    private static final Logger log = LoggerFactory.getLogger(LlmProxyController.class);
    private static final int MAX_REQUEST_BYTES = 64 * 1024 * 1024;
    private static final int BUFFER_BYTES = 8192;
    private static final String EVENT_STREAM = "text/event-stream";
    /** Claude Code stops retrying when this is above 60, which is right once a budget has run out. */
    private static final String BUDGET_RETRY_AFTER = "3600";
    private static final String UNAVAILABLE_RETRY_AFTER = "30";

    /** Forwarded unchanged. The credential and everything else stay behind. */
    private static final List<String> REQUEST_HEADERS = List.of("anthropic-version", "anthropic-beta", "content-type");
    private static final List<String> RESPONSE_HEADERS = List.of("content-type", "request-id", "retry-after", "x-should-retry");

    private final AnthropicUpstream upstream;
    private final LlmUsageRecorder recorder;
    private final TestRunRepository runs;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public LlmProxyController(
            AnthropicUpstream upstream,
            LlmUsageRecorder recorder,
            TestRunRepository runs,
            LlmProperties properties,
            ObjectMapper objectMapper) {
        this.upstream = upstream;
        this.recorder = recorder;
        this.runs = runs;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/messages")
    public void messages(
            @AuthenticationPrincipal RunnerPrincipal principal, HttpServletRequest request, HttpServletResponse response)
            throws IOException, InterruptedException {
        forward(principal, "/v1/messages", request, response, true);
    }

    /** Counting tokens is free, so it doesn't touch the budget. */
    @PostMapping("/messages/count_tokens")
    public void countTokens(
            @AuthenticationPrincipal RunnerPrincipal principal, HttpServletRequest request, HttpServletResponse response)
            throws IOException, InterruptedException {
        forward(principal, "/v1/messages/count_tokens", request, response, false);
    }

    private void forward(
            RunnerPrincipal principal, String path, HttpServletRequest request, HttpServletResponse response, boolean billable)
            throws IOException, InterruptedException {
        if (!properties.isConfigured()) {
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE, "api_error", "The LLM API key isn't configured.", null);
            return;
        }
        byte[] body = readBody(request);
        if (body == null) {
            writeError(response, HttpStatus.PAYLOAD_TOO_LARGE, "request_too_large", "The request is too large.", null);
            return;
        }
        String model = modelOf(body);
        if (model == null || !properties.allowedModels().contains(model)) {
            writeError(response, HttpStatus.BAD_REQUEST, "invalid_request_error", "This model isn't allowed for test runs.", null);
            return;
        }
        if (billable && isOverBudget(principal)) {
            writeError(response, HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error", "This run has used its token budget.", BUDGET_RETRY_AFTER);
            return;
        }

        HttpResponse<InputStream> answer;
        try {
            answer = upstream.send(withQuery(path, request), forwardedHeaders(request), body);
        } catch (IOException | CallNotPermittedException e) {
            log.warn("Could not reach the LLM API: {}", e.getClass().getSimpleName());
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE, "api_error", "The LLM API can't be reached.", UNAVAILABLE_RETRY_AFTER);
            return;
        }
        relay(principal, model, answer, response, billable);
    }

    /** Streams chunk by chunk, so the agent sees output as it's produced. Usage is always recorded. */
    private void relay(
            RunnerPrincipal principal, String model, HttpResponse<InputStream> answer, HttpServletResponse response, boolean billable)
            throws IOException {
        response.setStatus(answer.statusCode());
        RESPONSE_HEADERS.forEach(name ->
                answer.headers().firstValue(name).ifPresent(value -> response.setHeader(name, value)));

        boolean streaming = answer.headers().firstValue("content-type").orElse("").startsWith(EVENT_STREAM);
        SseUsageScanner scanner = new SseUsageScanner(objectMapper);
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        try (InputStream in = answer.body()) {
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
                if (billable && streaming) {
                    scanner.accept(buffer, read);
                } else if (billable) {
                    whole.write(buffer, 0, read);
                }
            }
        } finally {
            if (billable) {
                TokenCounts tokens = streaming ? scanner.tokens() : usageOf(whole.toByteArray());
                recorder.record(principal.runId(), model, answer.statusCode(), tokens);
            }
        }
    }

    private boolean isOverBudget(RunnerPrincipal principal) {
        TestRun run = runs.findById(principal.runId()).orElseThrow();
        return run.getTokenUsage() >= run.getTokenBudget();
    }

    private TokenCounts usageOf(byte[] body) {
        try {
            return TokenCounts.NONE.merge(objectMapper.readTree(body).path("usage"));
        } catch (IOException e) {
            return TokenCounts.NONE;
        }
    }

    private String modelOf(byte[] body) {
        try {
            JsonNode model = objectMapper.readTree(body).path("model");
            return model.isTextual() ? model.asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] readBody(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
        return body.length > MAX_REQUEST_BYTES ? null : body;
    }

    /** Claude Code adds {@code ?beta=true}. Only that is passed on. */
    private static String withQuery(String path, HttpServletRequest request) {
        return "true".equals(request.getParameter("beta")) ? path + "?beta=true" : path;
    }

    private static Map<String, String> forwardedHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        REQUEST_HEADERS.forEach(name -> {
            String value = request.getHeader(name);
            if (value != null) {
                headers.put(name, value);
            }
        });
        return headers;
    }

    /** Uses the API's own error shape, because the reader is Claude Code. */
    private void writeError(HttpServletResponse response, HttpStatus status, String type, String message, String retryAfter)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (retryAfter != null) {
            response.setHeader("retry-after", retryAfter);
        }
        response.getOutputStream().write(errorJson(type, message).getBytes(StandardCharsets.UTF_8));
    }

    private String errorJson(String type, String message) throws JsonProcessingException {
        return objectMapper.writeValueAsString(
                Map.of("type", "error", "error", Map.of("type", type, "message", message)));
    }
}
