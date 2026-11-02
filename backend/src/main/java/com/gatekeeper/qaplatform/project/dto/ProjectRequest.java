package com.gatekeeper.qaplatform.project.dto;

import com.gatekeeper.qaplatform.finding.Finding;
import com.gatekeeper.qaplatform.project.DesignSource;
import com.gatekeeper.qaplatform.project.FeatureSpec;
import com.gatekeeper.qaplatform.project.RepoConnection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Everything the intake wizard collects, saved in one transaction. Used to create and to edit.
 *
 * @param version the version the user was editing, required when updating so that changes made by
 *     someone else in the meantime aren't overwritten
 */
public record ProjectRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Pattern(regexp = ZOHO_ID, message = ZOHO_ID_MESSAGE) String zohoPortalId,
        @NotBlank @Pattern(regexp = ZOHO_ID, message = ZOHO_ID_MESSAGE) String zohoProjectId,
        @Valid DesignInput design,
        @NotEmpty @Size(max = 20) List<@NotNull @Valid RepositoryInput> repositories,
        @NotBlank @Size(max = 2048) String stagingUrl,
        @NotNull @Size(max = 50) List<@NotNull @Valid TestAccountInput> testAccounts,
        @NotEmpty @Size(max = 500) List<@NotNull @Valid FeatureInput> features,
        @NotEmpty @Size(max = 4) List<@NotNull @Valid DepartmentMappingInput> departmentMappings,
        Long version) {

    private static final String ZOHO_ID = "^$|[A-Za-z0-9_-]{1,64}";
    private static final String ZOHO_ID_MESSAGE = "must contain only letters, digits, hyphens and underscores";

    /**
     * @param figmaUrl required for FIGMA and must be absent for UPLOAD
     * @param token required for a new Figma design; when editing, leave empty to keep the stored one
     */
    public record DesignInput(
            @NotNull DesignSource.Type type, @Size(max = 2048) String figmaUrl, @Size(max = 512) String token) {

        @Override
        public String toString() {
            return "DesignInput[type=" + type + ", token=***]";
        }
    }

    public record RepositoryInput(
            @NotNull RepoConnection.Type type,
            @NotBlank
                    @Size(max = 255)
                    @Pattern(regexp = "^$|[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", message = "must look like owner/repository")
                    String repoFullName,
            @NotBlank
                    @Size(max = 255)
                    @Pattern(regexp = "^$|[A-Za-z0-9_./-]+", message = "is not a valid branch name")
                    String defaultBranch,
            @Positive Long installationId) {}

    /**
     * @param id set when editing an existing account
     * @param password required for a new account; when editing, leave empty to keep the stored one
     */
    public record TestAccountInput(
            UUID id,
            @NotBlank @Size(max = 100) String role,
            @NotBlank @Size(max = 320) String username,
            @Size(max = 1000) String password,
            @NotBlank @Size(max = 2048) String loginUrl) {

        public boolean hasPassword() {
            return password != null && !password.isBlank();
        }

        @Override
        public String toString() {
            return "TestAccountInput[id=" + id + ", role=" + role + ", username=" + username + ", password=***]";
        }
    }

    public record FeatureInput(
            @NotBlank @Size(max = 300) String title,
            @Size(max = 10_000) String description,
            @Size(max = 10_000) String acceptanceCriteria,
            @NotNull FeatureSpec.Priority priority) {}

    /** @param securityLeadZohoUserId required for the SECURITY category and ignored for others */
    public record DepartmentMappingInput(
            @NotNull Finding.Category category,
            @NotBlank @Pattern(regexp = ZOHO_ID, message = ZOHO_ID_MESSAGE) String defaultZohoUserId,
            @NotBlank @Pattern(regexp = ZOHO_ID, message = ZOHO_ID_MESSAGE) String fallbackZohoUserId,
            @Pattern(regexp = ZOHO_ID, message = ZOHO_ID_MESSAGE) String securityLeadZohoUserId) {}
}
