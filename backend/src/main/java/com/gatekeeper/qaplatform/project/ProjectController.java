package com.gatekeeper.qaplatform.project;

import com.gatekeeper.qaplatform.auth.AuthenticatedUser;
import com.gatekeeper.qaplatform.common.error.RequestValidationException;
import com.gatekeeper.qaplatform.common.web.PageResponse;
import com.gatekeeper.qaplatform.project.dto.CreateProjectRequest;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse.DesignUploadResponse;
import com.gatekeeper.qaplatform.project.dto.ProjectSummaryResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final FeatureCsvParser featureCsvParser;
    private final DesignUploadService designUploadService;

    public ProjectController(
            ProjectService projectService, FeatureCsvParser featureCsvParser, DesignUploadService designUploadService) {
        this.projectService = projectService;
        this.featureCsvParser = featureCsvParser;
        this.designUploadService = designUploadService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'QA_LEAD')")
    public ProjectDetailResponse create(
            @Valid @RequestBody CreateProjectRequest request, @AuthenticationPrincipal AuthenticatedUser actor) {
        return projectService.create(request, actor);
    }

    @GetMapping
    public PageResponse<ProjectSummaryResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return projectService.list(page, size);
    }

    @GetMapping("/{id}")
    public ProjectDetailResponse get(@PathVariable UUID id) {
        return projectService.get(id);
    }

    @PostMapping(path = "/{id}/design-uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'QA_LEAD')")
    public List<DesignUploadResponse> uploadDesigns(
            @PathVariable UUID id, @RequestPart("files") List<MultipartFile> files) {
        return designUploadService.upload(id, files);
    }

    /** Checks a CSV and returns its features for the wizard. Nothing is saved. */
    @PostMapping(path = "/feature-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'QA_LEAD')")
    public FeatureCsvParser.Result parseFeatureCsv(@RequestPart("file") MultipartFile file) throws IOException {
        if (file.getSize() > FeatureCsvParser.MAX_BYTES) {
            throw new RequestValidationException("file", "must be 1 MB or smaller");
        }
        return featureCsvParser.parse(file.getBytes());
    }
}
