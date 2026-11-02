package com.gatekeeper.qaplatform.project;

import com.gatekeeper.qaplatform.audit.Audited;
import com.gatekeeper.qaplatform.auth.AuthenticatedUser;
import com.gatekeeper.qaplatform.common.error.ConflictException;
import com.gatekeeper.qaplatform.common.error.RequestValidationException;
import com.gatekeeper.qaplatform.common.error.ResourceNotFoundException;
import com.gatekeeper.qaplatform.common.storage.StorageCleanup;
import com.gatekeeper.qaplatform.common.web.PageResponse;
import com.gatekeeper.qaplatform.finding.Finding;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse.DepartmentMappingResponse;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse.DesignResponse;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse.DesignUploadResponse;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse.FeatureResponse;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse.RepositoryResponse;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse.TestAccountResponse;
import com.gatekeeper.qaplatform.project.dto.ProjectRequest;
import com.gatekeeper.qaplatform.project.dto.ProjectRequest.DesignInput;
import com.gatekeeper.qaplatform.project.dto.ProjectRequest.TestAccountInput;
import com.gatekeeper.qaplatform.project.dto.ProjectSummaryResponse;
import com.gatekeeper.qaplatform.routing.DepartmentMapping;
import com.gatekeeper.qaplatform.routing.DepartmentMappingRepository;
import com.gatekeeper.qaplatform.run.TestRunRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final RepoConnectionRepository repositories;
    private final DesignSourceRepository designSources;
    private final DesignUploadRepository designUploads;
    private final TestAccountRepository testAccounts;
    private final FeatureSpecRepository features;
    private final DepartmentMappingRepository departmentMappings;
    private final TestRunRepository testRuns;
    private final ProjectRequestValidator validator;
    private final FigmaUrlParser figmaUrls;
    private final StorageCleanup storageCleanup;
    private final EntityManager entityManager;

    public ProjectService(
            ProjectRepository projects,
            RepoConnectionRepository repositories,
            DesignSourceRepository designSources,
            DesignUploadRepository designUploads,
            TestAccountRepository testAccounts,
            FeatureSpecRepository features,
            DepartmentMappingRepository departmentMappings,
            TestRunRepository testRuns,
            ProjectRequestValidator validator,
            FigmaUrlParser figmaUrls,
            StorageCleanup storageCleanup,
            EntityManager entityManager) {
        this.projects = projects;
        this.repositories = repositories;
        this.designSources = designSources;
        this.designUploads = designUploads;
        this.testAccounts = testAccounts;
        this.features = features;
        this.departmentMappings = departmentMappings;
        this.testRuns = testRuns;
        this.validator = validator;
        this.figmaUrls = figmaUrls;
        this.storageCleanup = storageCleanup;
        this.entityManager = entityManager;
    }

    /** Saves the project and everything in it, or nothing at all. */
    @Audited(
            action = "PROJECT_CREATE",
            target = "#result?.id()",
            details = "{name: #request.name(), testAccounts: #request.testAccounts().size(), figmaTokenStored: #request.design()?.token() != null}")
    @Transactional
    public ProjectDetailResponse create(ProjectRequest request, AuthenticatedUser actor) {
        validator.validateNew(request);

        Project project = new Project(request.name().trim(), request.stagingUrl().trim(), actor.email());
        project.setZohoPortalId(request.zohoPortalId());
        project.setZohoProjectId(request.zohoProjectId());
        UUID projectId = projects.save(project).getId();

        if (request.design() != null) {
            designSources.save(newDesignSource(projectId, request.design()));
        }
        saveRepositories(projectId, request);
        request.testAccounts().forEach(input -> testAccounts.save(newTestAccount(projectId, input)));
        saveFeatures(projectId, request);
        saveDepartmentMappings(projectId, request);
        return toDetail(project);
    }

    /**
     * Replaces the project's contents with the request. Stored credentials stay unless a new value
     * is sent, so the UI never needs to read them.
     */
    @Audited(
            action = "PROJECT_UPDATE",
            target = "#id",
            details = "{name: #request.name(), passwordsChanged: #request.testAccounts().?[hasPassword()].size(), figmaTokenChanged: #request.design()?.token() != null}")
    @Transactional
    public ProjectDetailResponse update(UUID id, ProjectRequest request) {
        Project project = find(id);
        if (request.version() == null) {
            throw new RequestValidationException("version", "must not be null");
        }
        if (request.version() != project.getVersion()) {
            throw new ConflictException("Someone else changed this project. Reload it and try again.");
        }

        Optional<DesignSource> savedDesign = designSources.findByProjectId(id).stream().findFirst();
        Map<UUID, TestAccount> savedAccounts = testAccounts.findByProjectId(id).stream()
                .collect(Collectors.toMap(TestAccount::getId, Function.identity()));
        boolean keepsFigmaToken = savedDesign.filter(source -> source.getToken() != null).isPresent()
                && request.design() != null
                && request.design().type() == DesignSource.Type.FIGMA;
        validator.validate(request, new ProjectRequestValidator.Existing(savedAccounts, keepsFigmaToken));

        project.setName(request.name().trim());
        project.setStagingUrl(request.stagingUrl().trim());
        project.setZohoPortalId(request.zohoPortalId());
        project.setZohoProjectId(request.zohoProjectId());
        // The version must also change when only the rows below this project change.
        entityManager.lock(project, LockModeType.OPTIMISTIC_FORCE_INCREMENT);

        syncDesign(id, savedDesign, request.design());
        syncTestAccounts(id, savedAccounts, request.testAccounts());
        // Bulk deletes run straight away, so the inserts below can't hit unique constraints.
        repositories.deleteAllForProject(id);
        features.deleteAllForProject(id);
        departmentMappings.deleteAllForProject(id);
        saveRepositories(id, request);
        saveFeatures(id, request);
        saveDepartmentMappings(id, request);

        entityManager.flush();
        return toDetail(project);
    }

    /**
     * Refuses to delete a project with test runs, because that would also erase its findings and
     * reports. Row deletion cascades in the database.
     */
    @Audited(action = "PROJECT_DELETE", target = "#id")
    @Transactional
    public void delete(UUID id) {
        Project project = find(id);
        if (testRuns.existsByProjectId(id)) {
            throw new ConflictException("This project has test runs, so it can't be deleted.");
        }
        designSources.findByProjectId(id).forEach(source -> storageCleanup.deleteAfterCommit(storageKeys(source)));
        projects.delete(project);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProjectSummaryResponse> list(int page, int size) {
        var request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(projects.findAll(request), ProjectSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public ProjectDetailResponse get(UUID id) {
        return toDetail(find(id));
    }

    private Project find(UUID id) {
        return projects.findById(id).orElseThrow(() -> new ResourceNotFoundException("Project not found"));
    }

    private void syncDesign(UUID projectId, Optional<DesignSource> saved, DesignInput input) {
        boolean sameType = saved.isPresent() && input != null && saved.get().getType() == input.type();
        if (sameType) {
            DesignSource source = saved.get();
            if (input.type() == DesignSource.Type.FIGMA) {
                String fileKey = figmaUrls.parseFileKey(input.figmaUrl()).orElseThrow();
                if (!fileKey.equals(source.getFigmaFileKey())) {
                    // Frames exported from the old file no longer describe this design.
                    storageCleanup.deleteAfterCommit(storageKeys(source));
                    designUploads.deleteAll(designUploads.findByDesignSourceIdOrderByFileNameAsc(source.getId()));
                }
                source.setFigmaFileKey(fileKey);
                if (input.token() != null && !input.token().isBlank()) {
                    source.setToken(input.token());
                }
            }
            return;
        }
        saved.ifPresent(source -> {
            storageCleanup.deleteAfterCommit(storageKeys(source));
            designSources.delete(source);
            // A project has one design source, so the old one must be gone before the insert.
            entityManager.flush();
        });
        if (input != null) {
            designSources.save(newDesignSource(projectId, input));
        }
    }

    private void syncTestAccounts(UUID projectId, Map<UUID, TestAccount> saved, List<TestAccountInput> inputs) {
        Set<UUID> kept = inputs.stream().map(TestAccountInput::id).collect(Collectors.toSet());
        saved.values().stream().filter(account -> !kept.contains(account.getId())).forEach(testAccounts::delete);
        entityManager.flush();
        for (TestAccountInput input : inputs) {
            if (input.id() == null) {
                testAccounts.save(newTestAccount(projectId, input));
            } else {
                TestAccount account = saved.get(input.id());
                account.setRole(input.role().trim());
                account.setUsername(input.username().trim());
                account.setLoginUrl(input.loginUrl().trim());
                if (input.hasPassword()) {
                    account.setPassword(input.password());
                }
            }
        }
    }

    private void saveRepositories(UUID projectId, ProjectRequest request) {
        request.repositories().forEach(input -> {
            RepoConnection repository = new RepoConnection(
                    projectId, input.type(), RepoConnection.Provider.GITHUB, input.repoFullName(), input.defaultBranch());
            repository.setInstallationId(input.installationId());
            repositories.save(repository);
        });
    }

    private void saveFeatures(UUID projectId, ProjectRequest request) {
        request.features().forEach(input -> features.save(new FeatureSpec(
                projectId, input.title().trim(), input.description(), input.acceptanceCriteria(), input.priority())));
    }

    private void saveDepartmentMappings(UUID projectId, ProjectRequest request) {
        request.departmentMappings().forEach(input -> {
            DepartmentMapping mapping = new DepartmentMapping(
                    projectId, input.category(), input.defaultZohoUserId(), input.fallbackZohoUserId());
            if (input.category() == Finding.Category.SECURITY) {
                mapping.setSecurityLeadZohoUserId(input.securityLeadZohoUserId());
            }
            departmentMappings.save(mapping);
        });
    }

    private static TestAccount newTestAccount(UUID projectId, TestAccountInput input) {
        return new TestAccount(
                projectId, input.role().trim(), input.username().trim(), input.password(), input.loginUrl().trim());
    }

    private DesignSource newDesignSource(UUID projectId, DesignInput input) {
        DesignSource source = new DesignSource(projectId, input.type());
        if (input.type() == DesignSource.Type.FIGMA) {
            source.setFigmaFileKey(figmaUrls.parseFileKey(input.figmaUrl()).orElseThrow());
            source.setToken(input.token());
        }
        return source;
    }

    private List<String> storageKeys(DesignSource source) {
        return designUploads.findByDesignSourceIdOrderByFileNameAsc(source.getId()).stream()
                .map(DesignUpload::getStorageKey)
                .toList();
    }

    private DesignResponse toDesignResponse(DesignSource source) {
        var uploadResponses = designUploads.findByDesignSourceIdOrderByFileNameAsc(source.getId()).stream()
                .map(upload -> new DesignUploadResponse(
                        upload.getId(), upload.getFileName(), upload.getContentType(), upload.getSizeBytes()))
                .toList();
        return new DesignResponse(source.getType(), source.getFigmaFileKey(), source.getToken() != null, uploadResponses);
    }

    private ProjectDetailResponse toDetail(Project project) {
        UUID id = project.getId();
        return new ProjectDetailResponse(
                id,
                project.getVersion(),
                project.getName(),
                project.getZohoPortalId(),
                project.getZohoProjectId(),
                project.getStagingUrl(),
                project.getCreatedBy(),
                project.getCreatedAt(),
                designSources.findByProjectId(id).stream()
                        .findFirst()
                        .map(this::toDesignResponse)
                        .orElse(null),
                repositories.findByProjectId(id).stream()
                        .map(repository -> new RepositoryResponse(
                                repository.getId(),
                                repository.getType(),
                                repository.getRepoFullName(),
                                repository.getDefaultBranch(),
                                repository.getInstallationId()))
                        .toList(),
                testAccounts.findByProjectId(id).stream()
                        .map(account -> new TestAccountResponse(
                                account.getId(), account.getRole(), account.getUsername(), account.getLoginUrl()))
                        .toList(),
                features.findByProjectIdOrderByTitleAsc(id).stream()
                        .map(feature -> new FeatureResponse(
                                feature.getId(),
                                feature.getTitle(),
                                feature.getDescription(),
                                feature.getAcceptanceCriteria(),
                                feature.getPriority()))
                        .toList(),
                departmentMappings.findByProjectId(id).stream()
                        .map(mapping -> new DepartmentMappingResponse(
                                mapping.getCategory(),
                                mapping.getDefaultZohoUserId(),
                                mapping.getFallbackZohoUserId(),
                                mapping.getSecurityLeadZohoUserId()))
                        .toList());
    }
}
