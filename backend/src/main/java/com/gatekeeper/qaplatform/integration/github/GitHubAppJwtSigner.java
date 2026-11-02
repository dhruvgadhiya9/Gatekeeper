package com.gatekeeper.qaplatform.integration.github;

import com.gatekeeper.qaplatform.common.error.ServiceNotConfiguredException;
import com.gatekeeper.qaplatform.config.GitHubProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.springframework.stereotype.Component;

/** Signs the short-lived JWT that identifies the GitHub App itself. */
@Component
public class GitHubAppJwtSigner {

    /** GitHub allows at most 10 minutes. */
    private static final Duration LIFETIME = Duration.ofMinutes(9);
    /** Tolerates clock differences between this server and GitHub. */
    private static final Duration BACKDATE = Duration.ofSeconds(60);

    private final GitHubProperties properties;
    private final Clock clock;
    private final PrivateKey privateKey;

    public GitHubAppJwtSigner(GitHubProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        // Parsed at startup, so a bad key stops the app rather than failing during later use.
        this.privateKey = properties.isConfigured() ? parsePrivateKey(properties.privateKey()) : null;
    }

    public String createJwt() {
        if (privateKey == null) {
            throw new ServiceNotConfiguredException("The GitHub App isn't set up yet.");
        }
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.appId())
                .issueTime(Date.from(now.minus(BACKDATE)))
                .expirationTime(Date.from(now.plus(LIFETIME)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            jwt.sign(new RSASSASigner(privateKey));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the GitHub App JWT", e);
        }
        return jwt.serialize();
    }

    /** GitHub issues PKCS#1 keys, which Java can't read directly, so they're wrapped as PKCS#8. */
    static PrivateKey parsePrivateKey(String configured) {
        try {
            String value = configured.trim();
            String pem = value.startsWith("-----BEGIN")
                    ? value
                    : new String(Base64.getMimeDecoder().decode(value), StandardCharsets.UTF_8);
            boolean isPkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
            String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            byte[] pkcs8 = isPkcs1 ? wrapPkcs1(der) : der;
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Do not chain the cause: its message could quote part of the key.
            throw new IllegalStateException("app.github.private-key isn't a valid RSA private key");
        }
    }

    private static byte[] wrapPkcs1(byte[] pkcs1) {
        int length = pkcs1.length;
        int total = length + 22;
        byte[] header = {
            0x30, (byte) 0x82, (byte) (total >> 8), (byte) total, // SEQUENCE
            0x02, 0x01, 0x00, // version 0
            0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00, // rsaEncryption
            0x04, (byte) 0x82, (byte) (length >> 8), (byte) length // OCTET STRING
        };
        byte[] pkcs8 = new byte[header.length + length];
        System.arraycopy(header, 0, pkcs8, 0, header.length);
        System.arraycopy(pkcs1, 0, pkcs8, header.length, length);
        return pkcs8;
    }
}
