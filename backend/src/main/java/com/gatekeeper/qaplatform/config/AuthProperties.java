package com.gatekeeper.qaplatform.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param publicUrl browser-facing address of the app, also the only allowed CORS origin
 * @param jwtSecret Base64-encoded HMAC key that signs login tokens
 * @param tokenTtl lifetime of a login token
 * @param cookieSecure whether cookies are limited to HTTPS
 * @param adminEmails verified emails that become ADMIN when they sign in
 * @param allowedEmailDomains domains allowed to sign in; empty allows all
 */
@Validated
@ConfigurationProperties("app.auth")
public record AuthProperties(
        @NotBlank String publicUrl,
        @NotBlank String jwtSecret,
        @NotNull Duration tokenTtl,
        boolean cookieSecure,
        List<String> adminEmails,
        List<String> allowedEmailDomains) {

    public AuthProperties {
        adminEmails = normalize(adminEmails);
        allowedEmailDomains = normalize(allowedEmailDomains);
    }

    private static List<String> normalize(List<String> values) {
        return values == null
                ? List.of()
                : values.stream().map(value -> value.trim().toLowerCase(Locale.ROOT)).toList();
    }

    @Override
    public String toString() {
        return "AuthProperties[publicUrl=" + publicUrl + ", jwtSecret=***]";
    }
}
