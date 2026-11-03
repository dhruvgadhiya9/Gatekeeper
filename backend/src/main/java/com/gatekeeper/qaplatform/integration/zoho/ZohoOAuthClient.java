package com.gatekeeper.qaplatform.integration.zoho;

import com.fasterxml.jackson.databind.JsonNode;
import com.gatekeeper.qaplatform.common.error.ServiceNotConfiguredException;
import com.gatekeeper.qaplatform.config.ZohoProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Zoho's OAuth endpoints. Secrets go in form bodies, so they never appear in a URL or access log. */
@Component
public class ZohoOAuthClient {

    public record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds) {

        @Override
        public String toString() {
            return "TokenResponse[expiresInSeconds=" + expiresInSeconds + ", tokens=***]";
        }
    }

    private final RestClient rest;
    private final ZohoProperties properties;

    public ZohoOAuthClient(RestClient.Builder builder, ZohoProperties properties) {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.rest = builder.clone().baseUrl(properties.accountsBase()).requestFactory(requestFactory).build();
        this.properties = properties;
    }

    public String authorizationUrl(String redirectUri, String state) {
        return UriComponentsBuilder.fromUriString(properties.accountsBase() + ZohoEndpoints.AUTHORIZE)
                .queryParam("client_id", properties.clientId())
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", String.join(",", properties.scopes()))
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("state", state)
                .encode()
                .build()
                .toUriString();
    }

    /** A code works only once, so this isn't retried. */
    @CircuitBreaker(name = "zoho")
    public TokenResponse exchangeCode(String code, String redirectUri) {
        MultiValueMap<String, String> form = clientForm();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        return post(form);
    }

    @Retry(name = "zoho")
    @CircuitBreaker(name = "zoho")
    public TokenResponse refresh(String refreshToken) {
        MultiValueMap<String, String> form = clientForm();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return post(form);
    }

    @CircuitBreaker(name = "zoho")
    public void revoke(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", refreshToken);
        rest.post()
                .uri(ZohoEndpoints.REVOKE)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity();
    }

    private MultiValueMap<String, String> clientForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        return form;
    }

    private TokenResponse post(MultiValueMap<String, String> form) {
        JsonNode response = rest.post()
                .uri(ZohoEndpoints.TOKEN)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        // Zoho reports OAuth failures such as a revoked token with HTTP 200 and an error field.
        if (response == null || !response.path("access_token").isTextual()) {
            throw new ServiceNotConfiguredException("The Zoho connection is no longer valid. An admin needs to reconnect it.");
        }
        return new TokenResponse(
                response.path("access_token").asText(),
                response.path("refresh_token").asText(null),
                response.path("expires_in").asLong(3600));
    }
}
