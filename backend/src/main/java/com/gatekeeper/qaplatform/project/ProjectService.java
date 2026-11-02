package com.gatekeeper.qaplatform.project;

import com.gatekeeper.qaplatform.audit.Audited;
import com.gatekeeper.qaplatform.auth.AuthenticatedUser;
import com.gatekeeper.qaplatform.common.error.ResourceNotFoundException;
import com.gatekeeper.qaplatform.common.web.PageResponse;
import com.gatekeeper.qaplatform.finding.Finding;
import com.gatekeeper.qaplatform.project.dto.CreateProjectRequest;
import com.gatekeeper.qaplatform.project.dto.CreateProjectRequest.DesignInput;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse.DepartmentMappingResponse;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse.DesignResponse;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse.FeatureResponse;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse.RepositoryResponse;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse.TestAccountResponse;
import com.gatekeeper.qaplatform.project.dto.ProjectSummaryResponse;
import com.gatekeeper.qaplatform.routing.DepartmentMapping;
import com.gatekeeper.qaplatform.routing.DepartmentMappingRepository;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final RepoConnectionRepository repositories;
    private final DesignSourceRepository designSources;
    private final TestAccountRepository testAccounts;
    private final FeatureSpecRepository features;
    private final DepartmentMappingRepository departmentMappings;
    private final ProjectRequestValidator validator;
    private final FigmaUrlParser figmaUrls;

    public ProjectService(
            ProjectRepository projects,
            RepoConnectionRepository repositories,
            DesignSourceRepository designSources,
            TestAccountRepository testAccounts,
            FeatureSpecRepository features,
            DepartmentMappingRepository departmentMappings,
            ProjectRequestValidator validator,
            FigmaUrlParser figmaUrls) {
        this.projects = projects;
        this.repositories = repositories;
        this.designSources = designSources;
        this.testAccounts = testAccounts;
        this.features = features;
        this.departmentMappings = departmentMappings;
        this.validator = validator;
        this.figmaUrls = figmaUrls;
    }

    /** Saves the project and everything in it, or nothing at all. */
    @Audited(
            action = "PROJECT_CREATE",
            target = "#result?.id()",
            details = "{name: #request.name(), testAccounts: #request.testAccounts().size(), figmaTokenStored: #request.design()?.token() != null}")
    @Transactional
    public ProjectDetailResponse create(CreateProjectRequest request, AuthenticatedUser actor) {
        validator.validate(request);

        Project project = new Project(request.name().trim(), request.stagingUrl().trim(), actor.email());
        project.setZohoPortalId(request.zohoPortalId());
        project.setZohoProjectId(request.zohoProjectId());
        UUID projectId = projects.save(project).getId();

        if (request.design() != null) {
            designSources.save(toDesignSource(projectId, request.design()));
        }
        request.repositories().forEach(input -> {
            RepoConnection repository = new RepoConnection(
                    projectId, input.type(), RepoConnection.Provider.GITHUB, input.repoFullName(), input.defaultBranch());
            repository.setInstallationId(input.installationId());
            repositories.save(repository);
        });
        request.testAccounts().forEach(input -> testAccounts.save(new TestAccount(
                projectId, input.role().trim(), input.username().trim(), input.password(), input.loginUrl().trim())));
        request.features().forEach(input -> features.save(new FeatureSpec(
                projectId, input.title().trim(), input.description(), input.acceptanceCriteria(), input.priority())));
        request.departmentMappings().forEach(input -> {
            DepartmentMapping mapping = new DepartmentMapping(
                    projectId, input.category(), input.defaultZohoUserId(), input.fallbackZohoUserId());
            if (input.category() == Finding.Category.SECURITY) {
                mapping.setSecurityLeadZohoUserId(input.securityLeadZohoUserId());
            }
            departmentMappings.save(mapping);
        });
        return toDetail(project);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProjectSummaryResponse> list(int page, int size) {
        var request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(projects.findAll(request), ProjectSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public ProjectDetailResponse get(UUID id) {
        return toDetail(projects.findById(id).orElseThrow(() -> new ResourceNotFoundException("Project not found")));
    }

    private DesignSource toDesignSource(UUID projectId, DesignInput input) {
        DesignSource source = new DesignSource(projectId, input.type());
        if (input.type() == DesignSource.Type.FIGMA) {
            source.setFigmaFileKey(figmaUrls.parseFileKey(input.figmaUrl()).orElseThrow());
            source.setToken(input.token());
        }
        return source;
    }

    private ProjectDetailResponse toDetail(Project project) {
        UUID id = project.getId();
        return new ProjectDetailResponse(
                id,
                project.getName(),
                project.getZohoPortalId(),
                project.getZohoProjectId(),
                project.getStagingUrl(),
                project.getCreatedBy(),
                project.getCreatedAt(),
                designSources.findByProjectId(id).stream()
                        .findFirst()
                        .map(source -> new DesignResponse(source.getType(), source.getFigmaFileKey(), source.getToken() != null))
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
