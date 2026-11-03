package com.gatekeeper.qaplatform.run.sandbox;

import com.gatekeeper.qaplatform.audit.Audited;
import com.gatekeeper.qaplatform.common.error.ConflictException;
import com.gatekeeper.qaplatform.common.error.ResourceNotFoundException;
import com.gatekeeper.qaplatform.config.LlmProperties;
import com.gatekeeper.qaplatform.config.SandboxProperties;
import com.gatekeeper.qaplatform.config.StorageProperties;
import com.gatekeeper.qaplatform.project.Project;
import com.gatekeeper.qaplatform.project.ProjectRepository;
import com.gatekeeper.qaplatform.project.StagingUrlPolicy;
import com.gatekeeper.qaplatform.run.TestRun;
import com.gatekeeper.qaplatform.run.TestRunRepository;
import com.gatekeeper.qaplatform.run.runner.JobTokenService;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Prepares a run's sandbox: its limits, its one credential, and where it may connect. */
@Service
public class RunSandboxService {

    private static final int HTTPS_PORT = 443;
    private static final Pattern SAFE_HOST = Pattern.compile("[A-Za-z0-9.-]+");

    private final TestRunRepository runs;
    private final ProjectRepository projects;
    private final StagingUrlPolicy stagingUrls;
    private final JobTokenService jobTokens;
    private final SandboxLauncher launcher;
    private final SandboxProperties sandbox;
    private final StorageProperties storage;
    private final LlmProperties llm;
    private final Clock clock;

    public RunSandboxService(
            TestRunRepository runs,
            ProjectRepository projects,
            StagingUrlPolicy stagingUrls,
            JobTokenService jobTokens,
            SandboxLauncher launcher,
            SandboxProperties sandbox,
            StorageProperties storage,
            LlmProperties llm,
            Clock clock) {
        this.runs = runs;
        this.projects = projects;
        this.stagingUrls = stagingUrls;
        this.jobTokens = jobTokens;
        this.launcher = launcher;
        this.sandbox = sandbox;
        this.storage = storage;
        this.llm = llm;
        this.clock = clock;
    }

    /** Sandboxes never get the real API key, so nothing here may add it to the environment. */
    @Audited(action = "RUN_SANDBOX_START", target = "#runId")
    public void launch(UUID runId) {
        TestRun run = runs.findById(runId).orElseThrow(() -> new ResourceNotFoundException("Run not found"));
        Project project = projects.findById(run.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        stagingUrls.findProblem(project.getStagingUrl()).ifPresent(problem -> {
            throw new ConflictException("This project's staging URL can't be tested: it " + problem + ".");
        });

        Instant now = clock.instant();
        Instant deadline = now.plus(sandbox.timeLimit());
        run.setTokenBudget(sandbox.tokenBudget());
        run.setDeadlineAt(deadline);
        run.setStartedAt(now);
        runs.save(run);

        URI staging = URI.create(project.getStagingUrl().trim());
        launcher.launch(new SandboxRequest(
                runId,
                environment(jobTokens.issue(runId, deadline)),
                allowedHosts(staging),
                allowedTlsPorts(staging),
                deadline));
    }

    public void stop(UUID runId) {
        launcher.stop(runId);
    }

    private Map<String, String> environment(String jobToken) {
        String backend = trimSlash(sandbox.backendUrl());
        return Map.of(
                "JOB_URL", backend + "/internal/runner",
                "JOB_TOKEN", jobToken,
                "ANTHROPIC_BASE_URL", backend + "/internal/runner/llm",
                "ANTHROPIC_API_KEY", jobToken,
                "ANTHROPIC_MODEL", llm.model(),
                "ANTHROPIC_DEFAULT_OPUS_MODEL", llm.model(),
                "ANTHROPIC_DEFAULT_SONNET_MODEL", llm.model(),
                "ANTHROPIC_DEFAULT_HAIKU_MODEL", llm.smallModel(),
                "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
    }

    private List<String> allowedHosts(URI staging) {
        Set<String> hosts = new LinkedHashSet<>(sandbox.egressAllowList());
        hosts.add(staging.getHost());
        hosts.add(URI.create(sandbox.backendUrl()).getHost());
        String storageEndpoint = storage.effectiveSandboxEndpoint();
        hosts.add(storageEndpoint == null || storageEndpoint.isBlank()
                ? "s3." + storage.region() + ".amazonaws.com"
                : URI.create(storageEndpoint).getHost());
        hosts.forEach(host -> {
            if (host == null || !SAFE_HOST.matcher(host).matches()) {
                throw new ConflictException("A sandbox can't be allowed to reach that host.");
            }
        });
        return new ArrayList<>(hosts);
    }

    private static List<Integer> allowedTlsPorts(URI staging) {
        return staging.getPort() > 0 && staging.getPort() != HTTPS_PORT
                ? List.of(HTTPS_PORT, staging.getPort())
                : List.of(HTTPS_PORT);
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
