package com.gatekeeper.qaplatform.run.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.qaplatform.audit.Audited;
import com.gatekeeper.qaplatform.common.error.ConflictException;
import com.gatekeeper.qaplatform.common.error.ResourceNotFoundException;
import com.gatekeeper.qaplatform.common.storage.ObjectStorage;
import com.gatekeeper.qaplatform.config.SandboxProperties;
import com.gatekeeper.qaplatform.integration.github.GitHubClient;
import com.gatekeeper.qaplatform.integration.github.GitHubModels.CloneCredentials;
import com.gatekeeper.qaplatform.project.DesignSourceRepository;
import com.gatekeeper.qaplatform.project.DesignUploadRepository;
import com.gatekeeper.qaplatform.project.FeatureSpecRepository;
import com.gatekeeper.qaplatform.project.Project;
import com.gatekeeper.qaplatform.project.ProjectRepository;
import com.gatekeeper.qaplatform.project.RepoConnection;
import com.gatekeeper.qaplatform.project.RepoConnectionRepository;
import com.gatekeeper.qaplatform.project.StagingUrlPolicy;
import com.gatekeeper.qaplatform.project.TestAccountRepository;
import com.gatekeeper.qaplatform.run.TestPlanRepository;
import com.gatekeeper.qaplatform.run.TestRun;
import com.gatekeeper.qaplatform.run.TestRunRepository;
import com.gatekeeper.qaplatform.run.runner.RunnerDtos.CallbackRequest;
import com.gatekeeper.qaplatform.run.runner.RunnerDtos.UploadFile;
import com.gatekeeper.qaplatform.run.runner.RunnerDtos.UploadTarget;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** What a sandbox can do with its job token: read its job, upload results and report back. */
@Service
public class RunnerService {

    private static final Duration UPLOAD_URL_LIFETIME = Duration.ofMinutes(30);

    private final TestRunRepository runs;
    private final ProjectRepository projects;
    private final RepoConnectionRepository repositories;
    private final TestAccountRepository testAccounts;
    private final FeatureSpecRepository features;
    private final DesignSourceRepository designSources;
    private final DesignUploadRepository designUploads;
    private final TestPlanRepository testPlans;
    private final GitHubClient github;
    private final ObjectStorage storage;
    private final StagingUrlPolicy stagingUrls;
    private final SandboxProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RunnerService(
            TestRunRepository runs,
            ProjectRepository projects,
            RepoConnectionRepository repositories,
            TestAccountRepository testAccounts,
            FeatureSpecRepository features,
            DesignSourceRepository designSources,
            DesignUploadRepository designUploads,
            TestPlanRepository testPlans,
            GitHubClient github,
            ObjectStorage storage,
            StagingUrlPolicy stagingUrls,
            SandboxProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.runs = runs;
        this.projects = projects;
        this.repositories = repositories;
        this.testAccounts = testAccounts;
        this.features = features;
        this.designSources = designSources;
        this.designUploads = designUploads;
        this.testPlans = testPlans;
        this.github = github;
        this.storage = storage;
        this.stagingUrls = stagingUrls;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Audited, because this hands out stored credentials. The deny-list may have changed, so it's rechecked. */
    @Audited(action = "RUNNER_JOB_FETCH", target = "#runId")
    public JobSpec jobSpec(UUID runId) {
        TestRun run = run(runId);
        Project project = projects.findById(run.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        stagingUrls.findProblem(project.getStagingUrl()).ifPresent(problem -> {
            throw new ConflictException("This project's staging URL can't be tested: it " + problem + ".");
        });

        List<JobSpec.DesignFrame> frames = designSources.findByProjectId(project.getId()).stream()
                .flatMap(source -> designUploads.findByDesignSourceIdOrderByFileNameAsc(source.getId()).stream())
                .map(upload -> new JobSpec.DesignFrame(
                        upload.getFileName(), storage.presignGet(upload.getStorageKey(), properties.timeLimit())))
                .toList();

        return new JobSpec(
                runId,
                project.getStagingUrl(),
                null,
                testPlans.findByRunId(runId).map(plan -> plan.getMarkdown()).orElse(null),
                new JobSpec.Budget(properties.timeLimit().toMinutes(), run.getTokenBudget()),
                repositories.findByProjectId(project.getId()).stream().map(this::toRepository).toList(),
                testAccounts.findByProjectId(project.getId()).stream()
                        .map(account -> new JobSpec.TestAccount(
                                account.getRole(), account.getUsername(), account.getPassword(), account.getLoginUrl()))
                        .toList(),
                frames,
                features.findByProjectIdOrderByTitleAsc(project.getId()).stream()
                        .map(feature -> new JobSpec.Feature(
                                feature.getTitle(),
                                feature.getDescription(),
                                feature.getAcceptanceCriteria(),
                                feature.getPriority().name()))
                        .toList());
    }

    /** Keys are built here and stay inside this run's prefix, whatever the sandbox asks for. */
    public List<UploadTarget> uploadTargets(UUID runId, List<UploadFile> files) {
        return files.stream()
                .map(file -> new UploadTarget(
                        file.path(),
                        storage.presignPut(storageKey(runId, file.path()), file.sizeBytes(), file.contentType(), UPLOAD_URL_LIFETIME)))
                .toList();
    }

    /** The first report ends the sandbox's access, because its token then stops working. */
    @Audited(action = "RUNNER_CALLBACK", target = "#runId", details = "{outcome: #request.outcome(), exitCode: #request.exitCode()}")
    public void finish(UUID runId, CallbackRequest request) {
        TestRun run = run(runId);
        run.finishSandbox(clock.instant(), request.exitCode(), toJson(request));
        runs.save(run);
    }

    static String storageKey(UUID runId, String path) {
        return "runs/" + runId + "/" + path;
    }

    private JobSpec.Repository toRepository(RepoConnection repository) {
        CloneCredentials credentials = github.isConfigured() && repository.getInstallationId() != null
                ? github.createCloneCredentials(repository.getInstallationId(), repository.getRepoFullName())
                : null;
        return new JobSpec.Repository(
                repository.getType().name(),
                repository.getRepoFullName(),
                repository.getDefaultBranch(),
                credentials != null ? credentials.cloneUrl() : "https://github.com/" + repository.getRepoFullName() + ".git",
                credentials != null ? credentials.token() : null);
    }

    private TestRun run(UUID runId) {
        return runs.findById(runId).orElseThrow(() -> new ResourceNotFoundException("Run not found"));
    }

    private String toJson(CallbackRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Callback isn't serializable", e);
        }
    }
}
