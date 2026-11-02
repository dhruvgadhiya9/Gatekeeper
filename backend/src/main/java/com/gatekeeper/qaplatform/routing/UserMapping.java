package com.gatekeeper.qaplatform.routing;

import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Links a GitHub username to a Zoho user, for owner resolution from CODEOWNERS and blame. */
@Entity
@Table(name = "user_mapping")
public class UserMapping extends BaseEntity {

    private String githubUsername;
    private String zohoUserId;

    protected UserMapping() {}

    public UserMapping(String githubUsername, String zohoUserId) {
        this.githubUsername = githubUsername;
        this.zohoUserId = zohoUserId;
    }

    public String getGithubUsername() {
        return githubUsername;
    }

    public String getZohoUserId() {
        return zohoUserId;
    }

    public void setZohoUserId(String zohoUserId) {
        this.zohoUserId = zohoUserId;
    }
}
