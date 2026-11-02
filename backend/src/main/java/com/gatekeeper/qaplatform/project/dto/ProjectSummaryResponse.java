package com.gatekeeper.qaplatform.project.dto;

import com.gatekeeper.qaplatform.project.Project;
import java.time.Instant;
import java.util.UUID;

public record ProjectSummaryResponse(
        UUID id, String name, String stagingUrl, String createdBy, Instant createdAt) {

    public static ProjectSummaryResponse from(Project project) {
        return new ProjectSummaryResponse(
                project.getId(),
                project.getName(),
                project.getStagingUrl(),
                project.getCreatedBy(),
                project.getCreatedAt());
    }
}
