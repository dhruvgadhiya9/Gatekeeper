package com.gatekeeper.qaplatform.integration.zoho;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;

/** A simulated Zoho, shared by the tests. It's separate from real data, and has no credentials. */
public final class ZohoTestSupport {

    public static final WireMockServer ZOHO = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    public static final String ACCESS_TOKEN = "zoho-access-token-value";
    public static final String REFRESH_TOKEN = "zoho-refresh-token-value";
    public static final String CLIENT_SECRET = "zoho-client-secret-value";

    private ZohoTestSupport() {}

    public static void register(DynamicPropertyRegistry registry) {
        if (!ZOHO.isRunning()) {
            ZOHO.start();
        }
        registry.add("app.zoho.client-id", () -> "zoho-client-id");
        registry.add("app.zoho.client-secret", () -> CLIENT_SECRET);
        registry.add("app.zoho.accounts-url", ZOHO::baseUrl);
        registry.add("app.zoho.api-url", ZOHO::baseUrl);
        registry.add("resilience4j.retry.configs.external.wait-duration", () -> "1ms");
        registry.add("resilience4j.retry.instances.zoho-rate-limited.wait-duration", () -> "1ms");
    }

    public static void stubTokens() {
        ZOHO.stubFor(post(urlEqualTo("/oauth/v2/token")).willReturn(okJson(
                "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"refresh_token\":\"" + REFRESH_TOKEN + "\",\"expires_in\":3600}")));
    }
}
