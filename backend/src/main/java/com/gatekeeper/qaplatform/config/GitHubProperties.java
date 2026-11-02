package com.gatekeeper.qaplatform.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param appId the GitHub App's ID; empty means the integration isn't set up yet
 * @param privateKey the App's PEM private key, plain or Base64-encoded
 */
@Validated
@ConfigurationProperties("app.github")
public record GitHubProperties(
        String appId, String privateKey, @NotBlank String apiUrl, @NotBlank String webUrl, @NotBlank String apiVersion) {

    public boolean isConfigured() {
        return appId != null && !appId.isBlank() && privateKey != null && !privateKey.isBlank();
    }

    @Override
    public String toString() {
        return "GitHubProperties[appId=" + appId + ", apiUrl=" + apiUrl + ", privateKey=***]";
    }
}
