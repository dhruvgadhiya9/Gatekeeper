package com.gatekeeper.qaplatform.run.runner;

import com.gatekeeper.qaplatform.config.SandboxProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Tokens that sandboxes use, one per run. They have their own key, issuer and audience, so they
 * can't be used as login tokens or the reverse.
 */
@Service
public class JobTokenService {

    private static final String ISSUER = "gatekeeper-runner";
    private static final String AUDIENCE = "gatekeeper-internal";
    private static final int MIN_KEY_BYTES = 32;

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Clock clock;

    public JobTokenService(SandboxProperties properties, Clock clock) {
        SecretKey key = decodeKey(properties.jobTokenSecret());
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbusDecoder =
                NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(AUDIENCE)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_audience"));
        nimbusDecoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER), audience));
        this.decoder = nimbusDecoder;
        this.clock = clock;
    }

    public String issue(UUID runId, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(runId.toString())
                .issuedAt(clock.instant())
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Returns the run this token is for, or empty if it's invalid or expired. */
    public Optional<UUID> parseRunId(String token) {
        try {
            return Optional.of(UUID.fromString(decoder.decode(token).getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static SecretKey decodeKey(String base64Key) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            // Do not chain the cause: its message can quote the key.
            throw new IllegalStateException("app.sandbox.job-token-secret must be valid Base64");
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("app.sandbox.job-token-secret must decode to at least 32 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
