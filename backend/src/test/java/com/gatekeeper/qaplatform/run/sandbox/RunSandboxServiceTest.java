package com.gatekeeper.qaplatform.run.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.gatekeeper.qaplatform.IntegrationTestBase;
import com.gatekeeper.qaplatform.common.error.ConflictException;
import com.gatekeeper.qaplatform.common.error.ServiceNotConfiguredException;
import com.gatekeeper.qaplatform.config.SandboxProperties;
import com.gatekeeper.qaplatform.project.Project;
import com.gatekeeper.qaplatform.project.ProjectRepository;
import com.gatekeeper.qaplatform.run.TestRun;
import com.gatekeeper.qaplatform.run.TestRunRepository;
import com.gatekeeper.qaplatform.run.runner.JobTokenService;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = {
    "app.llm.api-key=sk-ant-real-key-value",
    "app.sandbox.token-budget=12345",
    "app.storage.endpoint=http://minio:9000",
    "app.sandbox.egress-allow-list=registry.npmjs.org"
})
class RunSandboxServiceTest extends IntegrationTestBase {

    @MockitoBean private SandboxLauncher launcher;
    @Autowired private RunSandboxService service;
    @Autowired private ProjectRepository projects;
    @Autowired private TestRunRepository runs;
    @Autowired private JobTokenService jobTokens;
    @Autowired private SandboxProperties sandbox;

    @Test
    void sandboxGetsAJobTokenButNeverTheRealKey() {
        UUID runId = newRun("https://staging.app.test");

        service.launch(runId);

        SandboxRequest request = launched();
        assertThat(request.environment().values()).doesNotContain("sk-ant-real-key-value");
        assertThat(request.environment().toString()).doesNotContain("sk-ant-real-key-value");
        assertThat(jobTokens.parseRunId(request.environment().get("JOB_TOKEN"))).contains(runId);
        assertThat(request.environment().get("ANTHROPIC_API_KEY")).isEqualTo(request.environment().get("JOB_TOKEN"));
        assertThat(request.environment().get("ANTHROPIC_BASE_URL")).endsWith("/internal/runner/llm");
    }

    @Test
    void allowListIsRegistriesPlusThisRunsOwnHosts() {
        service.launch(newRun("https://staging.app.test"));

        assertThat(launched().allowedHosts())
                .containsExactlyInAnyOrder("registry.npmjs.org", "staging.app.test", "backend", "minio");
        assertThat(launched().allowedTlsPorts()).containsExactly(443);
    }

    @Test
    void aCustomStagingPortIsAllowedForTls() {
        service.launch(newRun("https://staging.app.test:8443"));

        assertThat(launched().allowedTlsPorts()).containsExactly(443, 8443);
    }

    @Test
    void limitsAreSetOnTheRun() {
        UUID runId = newRun("https://staging.app.test");

        service.launch(runId);

        TestRun run = runs.findById(runId).orElseThrow();
        assertThat(run.getTokenBudget()).isEqualTo(12345);
        // The database keeps microseconds, so these can differ below that.
        assertThat(run.getDeadlineAt()).isCloseTo(run.getStartedAt().plus(sandbox.timeLimit()), within(1, ChronoUnit.MILLIS));
        assertThat(launched().expiresAt()).isCloseTo(run.getDeadlineAt(), within(1, ChronoUnit.MILLIS));
    }

    @Test
    void productionUrlsAreRefusedAtLaunch() {
        UUID runId = newRun("https://www.corp.example");

        assertThatThrownBy(() -> service.launch(runId)).isInstanceOf(ConflictException.class);

        verify(launcher, never()).launch(any());
    }

    @Test
    void disabledLauncherExplainsWhatToDo() {
        assertThatThrownBy(() -> new DisabledSandboxLauncher().launch(null))
                .isInstanceOf(ServiceNotConfiguredException.class)
                .hasMessageContaining("SANDBOX_ENABLED");
    }

    private SandboxRequest launched() {
        ArgumentCaptor<SandboxRequest> captor = ArgumentCaptor.forClass(SandboxRequest.class);
        verify(launcher, org.mockito.Mockito.atLeastOnce()).launch(captor.capture());
        return captor.getValue();
    }

    private UUID newRun(String stagingUrl) {
        UUID projectId = projects.save(new Project("Demo", stagingUrl, "qa@example.test")).getId();
        return runs.save(new TestRun(projectId)).getId();
    }
}
