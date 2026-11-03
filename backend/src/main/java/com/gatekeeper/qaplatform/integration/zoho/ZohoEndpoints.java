package com.gatekeeper.qaplatform.integration.zoho;

/**
 * Every Zoho path in one place, so API changes are easy to apply. Paths are from the Zoho Projects
 * V3 documentation (projects.zoho.com/api-docs).
 */
final class ZohoEndpoints {

    static final String AUTHORIZE = "/oauth/v2/auth";
    static final String TOKEN = "/oauth/v2/token";
    static final String REVOKE = "/oauth/v2/token/revoke";

    static final String PORTALS = "/api/v3.1/portals?status=active";
    static final String PROJECTS = "/api/v3/portal/{portal}/projects?page={page}&per_page={size}";
    /** Documented as deprecated, but its replacement's path isn't published in the pages we can read. */
    static final String PROJECT_USERS = "/api/v3/portal/{portal}/projects/{project}/users?page={page}&per_page={size}";

    static final String ISSUES = "/api/v3/portal/{portal}/projects/{project}/issues";
    static final String ISSUES_PAGE = ISSUES + "?page={page}&per_page={size}&sort_by=created_time";
    static final String ISSUES_FILTERED = ISSUES_PAGE + "&filter={filter}";
    static final String ISSUE = ISSUES + "/{issue}";
    static final String ISSUE_COMMENTS = ISSUE + "/comments";
    static final String ISSUE_FOLLOWERS = ISSUE + "/followers";
    static final String ISSUE_ATTACHMENTS = ISSUE + "/attachments?attachment_ids={ids}";
    static final String ATTACHMENTS = "/api/v3/portal/{portal}/attachments";

    private ZohoEndpoints() {}
}
