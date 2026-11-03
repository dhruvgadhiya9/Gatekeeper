package com.gatekeeper.qaplatform.integration.zoho;

/** Values exchanged with {@link ZohoClient}. */
public final class ZohoModels {

    private ZohoModels() {}

    public record Portal(String id, String name) {}

    public record Project(String id, String name) {}

    /**
     * Zoho uses two IDs: an issue's assignee takes {@code zpuid}, while followers take {@code id}.
     * Gatekeeper stores {@code zpuid}, and looks up {@code id} when adding a follower.
     */
    public record User(String id, String zpuid, String name, String email) {}

    public record Issue(String id, String name, String description) {}

    /** @param severityId and the other IDs are optional, and left out of the request when null */
    public record NewIssue(
            String name,
            String description,
            String assigneeZpuid,
            String severityId,
            String classificationId,
            String moduleId) {}

    /** Only the fields set here are changed. */
    public record IssueChanges(String description, String assigneeZpuid, String severityId, String classificationId) {}
}
