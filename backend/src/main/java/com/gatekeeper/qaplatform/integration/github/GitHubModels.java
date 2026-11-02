package com.gatekeeper.qaplatform.integration.github;

import java.time.Instant;

/** Values returned by {@link GitHubClient}. */
public final class GitHubModels {

    private GitHubModels() {}

    public record Installation(long id, String account, String accountType) {}

    public record Repository(String fullName, String defaultBranch, boolean isPrivate) {}

    /** Read-only access to one repository for one run. Passed to the runner in Phase 6. */
    public record CloneCredentials(String token, Instant expiresAt, String cloneUrl) {

        @Override
        public String toString() {
            return "CloneCredentials[cloneUrl=" + cloneUrl + ", expiresAt=" + expiresAt + ", token=***]";
        }
    }

    /** Who last changed a range of lines. {@code authorLogin} is null if the email has no GitHub user. */
    public record BlameRange(
            int startLine, int endLine, String commitSha, Instant committedAt, String authorLogin, String authorEmail) {}
}
