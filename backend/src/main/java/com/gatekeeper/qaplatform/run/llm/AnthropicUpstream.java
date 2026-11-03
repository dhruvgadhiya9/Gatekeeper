package com.gatekeeper.qaplatform.run.llm;

import com.gatekeeper.qaplatform.config.LlmProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Sends a request to Anthropic with the real key added. This is raw HTTP, not the Java SDK, because
 * a gateway must forward bodies and {@code anthropic-beta} values it doesn't know about unchanged;
 * Claude Code's documentation requires this. Backend-originated calls use the SDK (Phase 8).
 */
@Component
public class AnthropicUpstream {

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final LlmProperties properties;

    public AnthropicUpstream(LlmProperties properties) {
        this.properties = properties;
    }

    /** There's no overall timeout, because a stream can legitimately last many minutes. */
    @CircuitBreaker(name = "anthropic")
    public HttpResponse<InputStream> send(String pathAndQuery, Map<String, String> headers, byte[] body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + pathAndQuery))
                .header("x-api-key", properties.apiKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
    }
}
