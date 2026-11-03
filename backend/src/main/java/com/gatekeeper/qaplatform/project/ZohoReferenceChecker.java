package com.gatekeeper.qaplatform.project;

import com.gatekeeper.qaplatform.common.error.FieldViolation;
import com.gatekeeper.qaplatform.common.error.RequestValidationException;
import com.gatekeeper.qaplatform.integration.zoho.ZohoClient;
import com.gatekeeper.qaplatform.integration.zoho.ZohoConnectionService;
import com.gatekeeper.qaplatform.integration.zoho.ZohoModels.User;
import com.gatekeeper.qaplatform.project.dto.ProjectRequest;
import com.gatekeeper.qaplatform.project.dto.ProjectRequest.DepartmentMappingInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Once Zoho is connected, a project's Zoho IDs must exist there, whether or not they came from the
 * wizard's dropdowns. Called before the database transaction starts.
 */
@Component
public class ZohoReferenceChecker {

    private final ZohoConnectionService connection;
    private final ZohoClient zoho;

    public ZohoReferenceChecker(ZohoConnectionService connection, ZohoClient zoho) {
        this.connection = connection;
        this.zoho = zoho;
    }

    public void check(ProjectRequest request) {
        if (!connection.isConnected()) {
            return;
        }
        List<FieldViolation> violations = new ArrayList<>();
        boolean portalExists = zoho.listPortals().stream().anyMatch(portal -> portal.id().equals(request.zohoPortalId()));
        if (!portalExists) {
            throw new RequestValidationException("zohoPortalId", "isn't a portal in the connected Zoho account");
        }
        boolean projectExists = zoho.listProjects(request.zohoPortalId()).stream()
                .anyMatch(project -> project.id().equals(request.zohoProjectId()));
        if (!projectExists) {
            throw new RequestValidationException("zohoProjectId", "isn't a project in that Zoho portal");
        }

        Set<String> members = zoho.listProjectUsers(request.zohoPortalId(), request.zohoProjectId()).stream()
                .map(User::zpuid)
                .collect(Collectors.toSet());
        for (int i = 0; i < request.departmentMappings().size(); i++) {
            DepartmentMappingInput mapping = request.departmentMappings().get(i);
            String path = "departmentMappings[" + i + "]";
            checkMember(mapping.defaultZohoUserId(), path + ".defaultZohoUserId", members, violations);
            checkMember(mapping.fallbackZohoUserId(), path + ".fallbackZohoUserId", members, violations);
            if (mapping.securityLeadZohoUserId() != null && !mapping.securityLeadZohoUserId().isBlank()) {
                checkMember(mapping.securityLeadZohoUserId(), path + ".securityLeadZohoUserId", members, violations);
            }
        }
        if (!violations.isEmpty()) {
            throw new RequestValidationException(violations);
        }
    }

    private static void checkMember(String id, String path, Set<String> members, List<FieldViolation> violations) {
        if (!members.contains(id)) {
            violations.add(new FieldViolation(path, "isn't a member of that Zoho project"));
        }
    }
}
