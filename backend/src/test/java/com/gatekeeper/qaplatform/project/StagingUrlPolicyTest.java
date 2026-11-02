package com.gatekeeper.qaplatform.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.gatekeeper.qaplatform.config.StagingProperties;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StagingUrlPolicyTest {

    private final StagingUrlPolicy policy = new StagingUrlPolicy(new StagingProperties(List.of("Corp.Example")));

    @ParameterizedTest
    @ValueSource(strings = {
        "https://staging.other.example",
        "http://staging.other.example:8080/app",
        "https://notcorp.example",
        "https://corp.example.attacker.test",
        "http://10.0.0.5:3000",
        "https://192.168.1.20",
    })
    void allows(String url) {
        assertThat(policy.findProblem(url)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://corp.example",
        "https://www.corp.example/path",
        "https://CORP.EXAMPLE",
        "https://corp.example.",
        "https://a.b.corp.example:8443",
    })
    void rejectsProductionDomains(String url) {
        assertThat(policy.findProblem(url)).hasValueSatisfying(problem -> assertThat(problem).contains("production"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost:8080",
        "http://LOCALHOST",
        "http://app.localhost",
        "http://127.0.0.1",
        "http://127.1.2.3",
        "http://0.0.0.0",
        "http://169.254.169.254/latest/meta-data",
        "http://[::1]/",
        "http://[::ffff:127.0.0.1]/",
        "http://[fe80::1]/",
        "http://2130706433/",
        "http://0x7f.1/",
        "http://127.1/",
        "http://metadata.google.internal/",
    })
    void rejectsLocalAndMetadataAddresses(String url) {
        assertThat(policy.findProblem(url)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "staging.other.example",
        "ftp://staging.other.example",
        "javascript:alert(1)",
        "file:///etc/passwd",
        "https://",
        "https://user:pass@staging.other.example",
        "https://exa mple.test",
        "",
    })
    void rejectsMalformedUrls(String url) {
        assertThat(policy.findProblem(url)).isPresent();
    }

    @ParameterizedTest
    @CsvSource({
        "https://app.test/login, https://app.test, true",
        "https://auth.app.test/login, https://app.test, true",
        "https://APP.test/login, https://app.test, true",
        "https://evil.test/login, https://app.test, false",
        "https://app.test.evil.test/login, https://app.test, false",
        "https://notapp.test/login, https://app.test, false",
    })
    void comparesLoginSites(String url, String staging, boolean expected) {
        assertThat(policy.isSameSite(url, staging)).isEqualTo(expected);
    }
}
