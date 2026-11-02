package com.gatekeeper.qaplatform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gatekeeper.qaplatform.config.AuthProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

    private static final Instant NOW = Instant.now();
    private static final String KEY_A = key(1);
    private static final String KEY_B = key(2);

    @Test
    void validTokenReturnsUserId() {
        UUID id = UUID.randomUUID();
        JwtTokenService service = service(KEY_A, NOW);

        assertThat(service.parseUserId(service.issue(id))).contains(id);
    }

    @Test
    void expiredTokenIsRejected() {
        String token = service(KEY_A, NOW.minus(Duration.ofHours(2))).issue(UUID.randomUUID());

        assertThat(service(KEY_A, NOW).parseUserId(token)).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String token = service(KEY_B, NOW).issue(UUID.randomUUID());

        assertThat(service(KEY_A, NOW).parseUserId(token)).isEmpty();
    }

    @Test
    void unsignedTokenIsRejected() {
        JwtTokenService service = service(KEY_A, NOW);
        String[] parts = service.issue(UUID.randomUUID()).split("\\.");
        String noneHeader = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());

        assertThat(service.parseUserId(noneHeader + "." + parts[1] + ".")).isEmpty();
    }

    @Test
    void garbageIsRejected() {
        assertThat(service(KEY_A, NOW).parseUserId("not-a-token")).isEmpty();
    }

    @Test
    void shortKeyIsRefusedAtStartup() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> service(shortKey, NOW)).isInstanceOf(IllegalStateException.class);
    }

    private static JwtTokenService service(String key, Instant now) {
        AuthProperties properties =
                new AuthProperties("https://app.test", key, Duration.ofHours(1), true, List.of(), List.of());
        return new JwtTokenService(properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String key(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
