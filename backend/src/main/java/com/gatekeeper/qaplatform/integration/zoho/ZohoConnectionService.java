package com.gatekeeper.qaplatform.integration.zoho;

import com.gatekeeper.qaplatform.audit.Audited;
import com.gatekeeper.qaplatform.common.error.ConflictException;
import com.gatekeeper.qaplatform.common.error.RequestValidationException;
import com.gatekeeper.qaplatform.common.error.ServiceNotConfiguredException;
import com.gatekeeper.qaplatform.config.AuthProperties;
import com.gatekeeper.qaplatform.config.ZohoProperties;
import com.gatekeeper.qaplatform.integration.zoho.ZohoOAuthClient.TokenResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/**
 * Holds the Zoho connection and access tokens. Zoho allows only ten new access tokens per refresh
 * token in ten minutes, so the current one is reused until shortly before it expires.
 */
@Service
public class ZohoConnectionService {

    public record Status(
            boolean configured, boolean connected, String connectedBy, Instant connectedAt, String dataCenter, boolean attachmentsEnabled) {}

    private record CachedToken(String token, Instant expiresAt) {

        @Override
        public String toString() {
            return "CachedToken[expiresAt=" + expiresAt + ", token=***]";
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ZohoConnectionService.class);
    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(5);
    private static final String CALLBACK_PATH = "/api/integrations/zoho/callback";

    private final ZohoOAuthClient oauth;
    private final ZohoConnectionRepository connections;
    private final ZohoProperties properties;
    private final AuthProperties authProperties;
    private final Clock clock;
    private volatile CachedToken cached;

    public ZohoConnectionService(
            ZohoOAuthClient oauth,
            ZohoConnectionRepository connections,
            ZohoProperties properties,
            AuthProperties authProperties,
            Clock clock) {
        this.oauth = oauth;
        this.connections = connections;
        this.properties = properties;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    public Status status() {
        Optional<ZohoConnection> connection = properties.isConfigured() ? connections.findFirstByOrderByCreatedAtDesc() : Optional.empty();
        return new Status(
                properties.isConfigured(),
                connection.isPresent(),
                connection.map(ZohoConnection::getConnectedBy).orElse(null),
                connection.map(ZohoConnection::getCreatedAt).orElse(null),
                properties.center().location(),
                properties.attachmentsEnabled());
    }

    public boolean isConnected() {
        return properties.isConfigured() && connections.findFirstByOrderByCreatedAtDesc().isPresent();
    }

    public String authorizationUrl(String state) {
        requireConfigured();
        return oauth.authorizationUrl(redirectUri(), state);
    }

    /** Replaces any earlier connection, and stores only the encrypted refresh token. */
    @Audited(action = "ZOHO_CONNECT", details = "{connectedBy: #connectedBy}")
    public synchronized void connect(String code, String location, String connectedBy) {
        requireConfigured();
        if (location != null && !location.isBlank() && !properties.center().location().equalsIgnoreCase(location.trim())) {
            throw new ConflictException("This Zoho account is in another data center. Update ZOHO_DATA_CENTER.");
        }
        if (code == null || code.isBlank()) {
            throw new RequestValidationException("code", "must not be blank");
        }
        TokenResponse tokens = oauth.exchangeCode(code, redirectUri());
        if (tokens.refreshToken() == null) {
            throw new ServiceNotConfiguredException("Zoho didn't return a refresh token. Revoke Gatekeeper's access in Zoho and connect again.");
        }
        connections.deleteAll();
        connections.saveAndFlush(new ZohoConnection(tokens.refreshToken(), connectedBy));
        remember(tokens);
    }

    /** Disconnects here even if Zoho can't be reached to revoke the token. */
    @Audited(action = "ZOHO_DISCONNECT")
    public synchronized void disconnect() {
        connections.findFirstByOrderByCreatedAtDesc().ifPresent(connection -> {
            try {
                oauth.revoke(connection.getRefreshToken());
            } catch (RestClientException e) {
                log.warn("Could not revoke the Zoho token: {}", e.getClass().getSimpleName());
            }
        });
        connections.deleteAll();
        cached = null;
    }

    public String accessToken() {
        CachedToken current = cached;
        if (isUsable(current)) {
            return current.token();
        }
        return refreshAccessToken();
    }

    /** Drops a token Zoho has rejected. */
    public void invalidate(String rejectedToken) {
        CachedToken current = cached;
        if (current != null && current.token().equals(rejectedToken)) {
            cached = null;
        }
    }

    private synchronized String refreshAccessToken() {
        CachedToken current = cached;
        if (isUsable(current)) {
            return current.token();
        }
        requireConfigured();
        ZohoConnection connection = connections.findFirstByOrderByCreatedAtDesc()
                .orElseThrow(() -> new ServiceNotConfiguredException("Zoho isn't connected yet. An admin can connect it in Settings."));
        return remember(oauth.refresh(connection.getRefreshToken()));
    }

    private String remember(TokenResponse tokens) {
        cached = new CachedToken(tokens.accessToken(), clock.instant().plusSeconds(tokens.expiresInSeconds()));
        return tokens.accessToken();
    }

    private boolean isUsable(CachedToken token) {
        return token != null && clock.instant().isBefore(token.expiresAt().minus(TOKEN_MARGIN));
    }

    private String redirectUri() {
        return authProperties.publicUrl() + CALLBACK_PATH;
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new ServiceNotConfiguredException("The Zoho OAuth client isn't set up yet.");
        }
    }
}
