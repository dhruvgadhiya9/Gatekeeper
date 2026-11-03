package com.gatekeeper.qaplatform.config;

import com.gatekeeper.qaplatform.integration.zoho.ZohoDataCenter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param dataCenter the Zoho data center of the connected account
 * @param accountsUrl overrides the data center's accounts address; for tests
 * @param apiUrl overrides the data center's Projects API address; for tests
 * @param attachmentsEnabled whether to request the broad WorkDrive scopes attachments need
 */
@ConfigurationProperties("app.zoho")
public record ZohoProperties(
        String clientId,
        String clientSecret,
        String dataCenter,
        String accountsUrl,
        String apiUrl,
        boolean attachmentsEnabled) {

    public ZohoProperties {
        // An unknown data center stops the app at startup.
        ZohoDataCenter.fromLocation(dataCenter);
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    public ZohoDataCenter center() {
        return ZohoDataCenter.fromLocation(dataCenter);
    }

    public String accountsBase() {
        return accountsUrl != null && !accountsUrl.isBlank() ? accountsUrl : center().accountsUrl();
    }

    public String apiBase() {
        return apiUrl != null && !apiUrl.isBlank() ? apiUrl : center().apiUrl();
    }

    public List<String> scopes() {
        List<String> scopes = new ArrayList<>(List.of(
                "ZohoProjects.portals.READ",
                "ZohoProjects.projects.READ",
                "ZohoProjects.users.READ",
                "ZohoProjects.bugs.READ",
                "ZohoProjects.bugs.CREATE",
                "ZohoProjects.bugs.UPDATE"));
        if (attachmentsEnabled) {
            scopes.addAll(List.of("ZohoProjects.portals.CREATE", "WorkDrive.workspace.ALL", "WorkDrive.files.ALL"));
        }
        return scopes;
    }

    @Override
    public String toString() {
        return "ZohoProperties[dataCenter=" + dataCenter + ", clientId=" + clientId + ", clientSecret=***]";
    }
}
