package com.gatekeeper.qaplatform.integration.figma;

import com.fasterxml.jackson.databind.JsonNode;
import com.gatekeeper.qaplatform.config.FigmaProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Reads Figma files using each project's own token, which only ever travels in a request header. */
@Component
public class FigmaClient {

    public record Frame(String id, String name, String page) {}

    private static final String TOKEN_HEADER = "X-Figma-Token";
    private static final int MAX_IMAGE_BYTES = 50 * 1024 * 1024;
    private static final Pattern IP_OR_LOCAL_HOST = Pattern.compile("^(localhost|.*\\.localhost|[\\d.]+|\\[.*\\])$");

    private final RestClient api;
    private final RestClient images;
    private final FigmaProperties properties;

    public FigmaClient(RestClient.Builder builder, FigmaProperties properties) {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        this.api = builder.clone().baseUrl(properties.apiUrl()).requestFactory(requestFactory).build();
        this.images = builder.clone().requestFactory(requestFactory).build();
        this.properties = properties;
    }

    /** Depth 2 returns pages and the top-level objects on them, without the whole file. */
    @Retry(name = "figma")
    @CircuitBreaker(name = "figma")
    public List<Frame> listFrames(String token, String fileKey) {
        JsonNode file = api.get()
                .uri("/v1/files/{key}?depth=2", fileKey)
                .header(TOKEN_HEADER, token)
                .retrieve()
                .body(JsonNode.class);
        List<Frame> frames = new ArrayList<>();
        for (JsonNode page : file.path("document").path("children")) {
            for (JsonNode node : page.path("children")) {
                if ("FRAME".equals(node.path("type").asText())) {
                    frames.add(new Frame(node.path("id").asText(), node.path("name").asText(), page.path("name").asText()));
                }
            }
        }
        return frames;
    }

    /**
     * Returns an image URL per frame. Figma gives null for a frame that failed to render, so those
     * are left out. Scale 1 keeps real pixel sizes for comparing against screenshots.
     */
    @Retry(name = "figma")
    @CircuitBreaker(name = "figma")
    public Map<String, String> renderFrames(String token, String fileKey, List<String> frameIds) {
        JsonNode response = api.get()
                .uri("/v1/images/{key}?ids={ids}&format=png&scale=1", fileKey, String.join(",", frameIds))
                .header(TOKEN_HEADER, token)
                .retrieve()
                .body(JsonNode.class);
        Map<String, String> urls = new HashMap<>();
        response.path("images").properties().forEach(entry -> {
            if (entry.getValue().isTextual()) {
                urls.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return urls;
    }

    /**
     * Downloads a rendered image. The URL comes from a response, so it's checked first, and it never
     * receives the Figma token.
     */
    @Retry(name = "figma")
    @CircuitBreaker(name = "figma")
    public byte[] downloadImage(String url) {
        URI uri = requireSafeImageUrl(url);
        return images.get().uri(uri).exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RestClientException("Image download failed with status " + response.getStatusCode().value());
            }
            try (InputStream body = response.getBody()) {
                byte[] bytes = body.readNBytes(MAX_IMAGE_BYTES + 1);
                if (bytes.length > MAX_IMAGE_BYTES) {
                    throw new RestClientException("Image is larger than the limit");
                }
                return bytes;
            } catch (IOException e) {
                throw new RestClientException("Image download failed", e);
            }
        });
    }

    private URI requireSafeImageUrl(String url) {
        URI uri = URI.create(url);
        boolean fromConfiguredApi = url.startsWith(properties.apiUrl() + "/");
        boolean safePublicUrl = "https".equals(uri.getScheme())
                && uri.getHost() != null
                && !IP_OR_LOCAL_HOST.matcher(uri.getHost()).matches();
        if (!fromConfiguredApi && !safePublicUrl) {
            throw new IllegalArgumentException("Figma returned an image address that isn't allowed");
        }
        return uri;
    }
}
