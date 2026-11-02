package com.gatekeeper.qaplatform.integration.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gatekeeper.qaplatform.common.error.ServiceNotConfiguredException;
import com.gatekeeper.qaplatform.config.GitHubProperties;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GitHubAppJwtSignerTest {

    @Test
    void readsPkcs8KeysAsPlainPemOrBase64() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] der = generator.generateKeyPair().getPrivate().getEncoded();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END PRIVATE KEY-----";

        assertThat(GitHubAppJwtSigner.parsePrivateKey(pem).getEncoded()).isEqualTo(der);
        assertThat(GitHubAppJwtSigner.parsePrivateKey(Base64.getEncoder().encodeToString(pem.getBytes())).getEncoded())
                .isEqualTo(der);
    }

    @Test
    void badKeyStopsStartupWithoutQuotingTheKey() {
        String bad = "-----BEGIN RSA PRIVATE KEY-----\nc2VjcmV0LWtleS12YWx1ZQ==\n-----END RSA PRIVATE KEY-----";

        assertThatThrownBy(() -> new GitHubAppJwtSigner(properties("1", bad), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause()
                .hasMessageNotContaining("c2VjcmV0");
    }

    @Test
    void withoutConfigurationTheAppStartsButCallsExplainWhy() {
        GitHubAppJwtSigner signer = new GitHubAppJwtSigner(properties("", ""), Clock.systemUTC());

        assertThatThrownBy(signer::createJwt).isInstanceOf(ServiceNotConfiguredException.class);
    }

    @Test
    void propertiesNeverPrintTheKey() {
        assertThat(properties("1", "secret-key").toString()).doesNotContain("secret-key");
    }

    private static GitHubProperties properties(String appId, String key) {
        return new GitHubProperties(appId, key, "https://api.test", "https://web.test", "2026-03-10");
    }
}
