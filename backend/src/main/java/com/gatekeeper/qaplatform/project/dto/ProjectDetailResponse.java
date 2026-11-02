package com.gatekeeper.qaplatform.project.dto;

import com.gatekeeper.qaplatform.finding.Finding;
import com.gatekeeper.qaplatform.project.DesignSource;
import com.gatekeeper.qaplatform.project.FeatureSpec;
import com.gatekeeper.qaplatform.project.RepoConnection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Credentials never appear here. Only the fact that one is stored is reported. */
public record ProjectDetailResponse(
        UUID id,
        String name,
        String zohoPortalId,
        String zohoProjectId,
        String stagingUrl,
        String createdBy,
        Instant createdAt,
        DesignResponse design,
        List<RepositoryResponse> repositories,
        List<TestAccountResponse> testAccounts,
        List<FeatureResponse> features,
        List<DepartmentMappingResponse> departmentMappings) {

    public record DesignResponse(
            DesignSource.Type type, String figmaFileKey, boolean tokenStored, List<DesignUploadResponse> uploads) {}

    public record DesignUploadResponse(UUID id, String fileName, String contentType, long sizeBytes) {}

    public record RepositoryResponse(
            UUID id, RepoConnection.Type type, String repoFullName, String defaultBranch, Long installationId) {}

    public record TestAccountResponse(UUID id, String role, String username, String loginUrl) {}

    public record FeatureResponse(
            UUID id, String title, String description, String acceptanceCriteria, FeatureSpec.Priority priority) {}

    public record DepartmentMappingResponse(
            Finding.Category category,
            String defaultZohoUserId,
            String fallbackZohoUserId,
            String securityLeadZohoUserId) {}
}
