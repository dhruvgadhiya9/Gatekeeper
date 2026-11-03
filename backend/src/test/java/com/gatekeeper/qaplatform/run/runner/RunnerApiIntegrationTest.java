package com.gatekeeper.qaplatform.run.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.qaplatform.IntegrationTestBase;
import com.gatekeeper.qaplatform.audit.AuditEventRepository;
import com.gatekeeper.qaplatform.auth.AppUser;
import com.gatekeeper.qaplatform.auth.AppUserRepository;
import com.gatekeeper.qaplatform.auth.JwtTokenService;
import com.gatekeeper.qaplatform.auth.Role;
import com.gatekeeper.qaplatform.project.FeatureSpec;
import com.gatekeeper.qaplatform.project.FeatureSpecRepository;
import com.gatekeeper.qaplatform.project.Project;
import com.gatekeeper.qaplatform.project.ProjectRepository;
import com.gatekeeper.qaplatform.project.TestAccount;
import com.gatekeeper.qaplatform.project.TestAccountRepository;
import com.gatekeeper.qaplatform.run.TestRun;
import com.gatekeeper.qaplatform.run.TestRunRepository;
import jakarta.servlet.http.Cookie;
import com.gatekeeper.qaplatform.config.SandboxProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@ExtendWith(OutputCaptureExtension.class)
class RunnerApiIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "runner-test-password";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JobTokenService jobTokens;
    @Autowired private SandboxProperties sandboxProperties;
    @Autowired private JwtTokenService loginTokens;
    @Autowired private ProjectRepository projects;
    @Autowired private TestRunRepository runs;
    @Autowired private TestAccountRepository accounts;
    @Autowired private FeatureSpecRepository features;
    @Autowired private AppUserRepository users;
    @Autowired private AuditEventRepository auditEvents;

    private UUID runId;
    private String token;

    @BeforeEach
    void setUp() {
        runId = newRun("https://staging.app.test");
        token = jobTokens.issue(runId, Instant.now().plus(1, ChronoUnit.HOURS));
    }

    @Test
    void jobContainsWhatTheAgentNeedsAndIsAudited() throws Exception {
        job(token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.stagingUrl").value("https://staging.app.test"))
                .andExpect(jsonPath("$.testAccounts[0].password").value(PASSWORD))
                .andExpect(jsonPath("$.features[0].title").value("Login"))
                .andExpect(jsonPath("$.budget.maxTokens").value(1000));

        assertThat(auditEvents.findAll())
                .anyMatch(event -> "RUNNER_JOB_FETCH".equals(event.getAction())
                        && runId.toString().equals(event.getTarget())
                        && !event.getDetailsJson().contains(PASSWORD));
    }

    @Test
    void passwordsNeverReachTheLogs(CapturedOutput output) throws Exception {
        job(token).andExpect(status().isOk());

        assertThat(output.getAll()).doesNotContain(PASSWORD);
    }

    @Test
    void requestsWithoutAValidJobTokenAreRefused() throws Exception {
        mockMvc.perform(get("/internal/runner/job")).andExpect(status().isUnauthorized());
        job("forged").andExpect(status().isUnauthorized());
    }

    @Test
    void loginTokensAndCookiesAreNotAcceptedByTheRunnerApi() throws Exception {
        String login = loginTokens.issue(admin().getId());

        job(login).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/runner/job").cookie(new Cookie("GK_TOKEN", login)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jobTokensAreNotAcceptedByTheRestOfTheApi() throws Exception {
        mockMvc.perform(get("/api/me").cookie(new Cookie("GK_TOKEN", token))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/projects").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenAndPastDeadlineAreRefused() throws Exception {
        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        JobTokenService issuedInThePast = new JobTokenService(sandboxProperties, Clock.fixed(twoHoursAgo, ZoneOffset.UTC));
        job(issuedInThePast.issue(runId, twoHoursAgo.plus(1, ChronoUnit.HOURS))).andExpect(status().isUnauthorized());

        TestRun run = runs.findById(runId).orElseThrow();
        run.setDeadlineAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        runs.save(run);
        job(token).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenStopsWorkingWhenTheRunEnds() throws Exception {
        TestRun run = runs.findById(runId).orElseThrow();
        run.setStatus(TestRun.Status.CANCELLED);
        runs.save(run);

        job(token).andExpect(status().isUnauthorized());
    }

    @Test
    void productionUrlAddedToTheDenyListLaterStopsTheJob() throws Exception {
        UUID denied = newRun("https://www.corp.example");

        job(jobTokens.issue(denied, Instant.now().plus(1, ChronoUnit.HOURS))).andExpect(status().isConflict());
    }

    @Test
    void uploadsAreSignedInsideThisRunsPrefix() throws Exception {
        send("/internal/runner/uploads", Map.of("files", List.of(
                        Map.of("path", "results/findings.json", "sizeBytes", 10, "contentType", "application/json"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploads[0].url").value("https://storage.test/put/runs/" + runId + "/results/findings.json"));
    }

    @Test
    void uploadPathsCantEscapeTheResultsFolder() throws Exception {
        for (String path : List.of("../../other-run/secret", "results/../../x", "/etc/passwd", "job.json", "results/a b")) {
            send("/internal/runner/uploads", Map.of("files", List.of(
                            Map.of("path", path, "sizeBytes", 10, "contentType", "application/json"))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void uploadsMustStateAReasonableSize() throws Exception {
        send("/internal/runner/uploads", Map.of("files", List.of(
                        Map.of("path", "results/a", "sizeBytes", 500L * 1024 * 1024, "contentType", "text/plain"))))
                .andExpect(status().isBadRequest());
        send("/internal/runner/uploads", Map.of("files", List.of(
                        Map.of("path", "results/a", "sizeBytes", 0, "contentType", "text/plain"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void callbackRecordsTheOutcomeAndEndsAccess() throws Exception {
        send("/internal/runner/callback", Map.of(
                        "outcome", "COMPLETED",
                        "exitCode", 0,
                        "files", List.of("results/findings.json"),
                        "agentSummary", "{\"total_cost_usd\":1.5}"))
                .andExpect(status().isNoContent());

        TestRun run = runs.findById(runId).orElseThrow();
        assertThat(run.getSandboxFinishedAt()).isNotNull();
        assertThat(run.getAgentExitCode()).isZero();
        assertThat(run.getResultsManifest()).contains("results/findings.json");
        assertThat(auditEvents.findAll()).anyMatch(event -> "RUNNER_CALLBACK".equals(event.getAction()));
        job(token).andExpect(status().isUnauthorized());
    }

    @Test
    void oneRunsTokenOnlyEverSeesItsOwnRun() throws Exception {
        UUID other = newRun("https://other.app.test");

        job(jobTokens.issue(other, Instant.now().plus(1, ChronoUnit.HOURS)))
                .andExpect(jsonPath("$.stagingUrl").value("https://other.app.test"));
        job(token).andExpect(jsonPath("$.stagingUrl").value("https://staging.app.test"));
    }

    private UUID newRun(String stagingUrl) {
        UUID projectId = projects.save(new Project("Demo", stagingUrl, "qa@example.test")).getId();
        accounts.save(new TestAccount(projectId, "admin", "qa@app.test", PASSWORD, stagingUrl + "/login"));
        features.save(new FeatureSpec(projectId, "Login", "d", "a", FeatureSpec.Priority.HIGH));
        TestRun run = new TestRun(projectId);
        run.setTokenBudget(1000);
        return runs.save(run).getId();
    }

    private ResultActions job(String jobToken) throws Exception {
        return mockMvc.perform(get("/internal/runner/job").header("Authorization", "Bearer " + jobToken));
    }

    private ResultActions send(String path, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private AppUser admin() {
        String id = UUID.randomUUID().toString();
        AppUser user = new AppUser("https://idp.example.test", id, id + "@example.test");
        user.setRole(Role.ADMIN);
        return users.save(user);
    }
}
