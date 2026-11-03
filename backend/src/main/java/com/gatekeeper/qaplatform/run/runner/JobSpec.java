package com.gatekeeper.qaplatform.run.runner;

import java.util.List;
import java.util.UUID;

/**
 * Everything a sandbox gets for one run. This is the one response that contains test account
 * passwords and clone tokens, so they're masked in {@code toString} and must never be logged.
 *
 * @param approvedPlan the test plan approved in the UI; null until Phase 7 fills it in
 * @param startCommand run in the first repository to start the app locally; null uses the staging URL
 */
public record JobSpec(
        UUID runId,
        String stagingUrl,
        String startCommand,
        String approvedPlan,
        Budget budget,
        List<Repository> repos,
        List<TestAccount> testAccounts,
        List<DesignFrame> designFrames,
        List<Feature> features) {

    public record Budget(long maxMinutes, long maxTokens) {}

    /** @param cloneToken read-only for this one repository; null if the GitHub App isn't connected */
    public record Repository(String type, String fullName, String defaultBranch, String cloneUrl, String cloneToken) {

        @Override
        public String toString() {
            return "Repository[fullName=" + fullName + ", cloneToken=***]";
        }
    }

    public record TestAccount(String role, String username, String password, String loginUrl) {

        @Override
        public String toString() {
            return "TestAccount[role=" + role + ", username=" + username + ", password=***]";
        }
    }

    public record DesignFrame(String fileName, String downloadUrl) {

        @Override
        public String toString() {
            return "DesignFrame[fileName=" + fileName + ", downloadUrl=***]";
        }
    }

    public record Feature(String title, String description, String acceptanceCriteria, String priority) {}

    @Override
    public String toString() {
        return "JobSpec[runId=" + runId + "]";
    }
}
