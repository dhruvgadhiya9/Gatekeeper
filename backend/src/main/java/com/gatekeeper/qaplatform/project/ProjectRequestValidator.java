package com.gatekeeper.qaplatform.project;

import com.gatekeeper.qaplatform.common.error.FieldViolation;
import com.gatekeeper.qaplatform.common.error.RequestValidationException;
import com.gatekeeper.qaplatform.finding.Finding;
import com.gatekeeper.qaplatform.project.dto.CreateProjectRequest;
import com.gatekeeper.qaplatform.project.dto.CreateProjectRequest.DepartmentMappingInput;
import com.gatekeeper.qaplatform.project.dto.CreateProjectRequest.DesignInput;
import com.gatekeeper.qaplatform.project.dto.CreateProjectRequest.RepositoryInput;
import com.gatekeeper.qaplatform.project.dto.CreateProjectRequest.TestAccountInput;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Rules that span several fields. Field-level rules are annotations on the request. */
@Component
public class ProjectRequestValidator {

    private static final Set<Finding.Category> REQUIRED_DEPARTMENTS = EnumSet.of(
            Finding.Category.DESIGN, Finding.Category.FRONTEND, Finding.Category.BACKEND, Finding.Category.SECURITY);

    private final StagingUrlPolicy stagingUrls;
    private final FigmaUrlParser figmaUrls;

    public ProjectRequestValidator(StagingUrlPolicy stagingUrls, FigmaUrlParser figmaUrls) {
        this.stagingUrls = stagingUrls;
        this.figmaUrls = figmaUrls;
    }

    /** @throws RequestValidationException listing every problem, so the user can fix them together */
    public void validate(CreateProjectRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        stagingUrls.findProblem(request.stagingUrl())
                .ifPresent(problem -> violations.add(new FieldViolation("stagingUrl", problem)));
        checkDesign(request.design(), violations);
        checkRepositories(request.repositories(), violations);
        checkTestAccounts(request.testAccounts(), request.stagingUrl(), violations);
        checkDepartments(request.departmentMappings(), violations);
        if (!violations.isEmpty()) {
            throw new RequestValidationException(violations);
        }
    }

    private void checkDesign(DesignInput design, List<FieldViolation> violations) {
        if (design == null) {
            return;
        }
        if (design.type() == DesignSource.Type.FIGMA) {
            if (figmaUrls.parseFileKey(design.figmaUrl()).isEmpty()) {
                violations.add(new FieldViolation("design.figmaUrl", "must be a figma.com file or design link"));
            }
            if (design.token() == null || design.token().isBlank()) {
                violations.add(new FieldViolation("design.token", "must not be blank"));
            }
        } else if (design.figmaUrl() != null || design.token() != null) {
            violations.add(new FieldViolation("design.type", "an uploaded design can't have a Figma link or token"));
        }
    }

    private static void checkRepositories(List<RepositoryInput> repositories, List<FieldViolation> violations) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < repositories.size(); i++) {
            if (!seen.add(repositories.get(i).repoFullName().toLowerCase(Locale.ROOT))) {
                violations.add(new FieldViolation("repositories[" + i + "].repoFullName", "is listed more than once"));
            }
        }
    }

    private void checkTestAccounts(
            List<TestAccountInput> accounts, String stagingUrl, List<FieldViolation> violations) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < accounts.size(); i++) {
            TestAccountInput account = accounts.get(i);
            String path = "testAccounts[" + i + "]";
            if (!seen.add(account.role() + "\n" + account.username())) {
                violations.add(new FieldViolation(path + ".username", "is listed more than once for this role"));
            }
            // These credentials get typed into this page, so it must belong to the staging site.
            stagingUrls.findProblem(account.loginUrl()).ifPresentOrElse(
                    problem -> violations.add(new FieldViolation(path + ".loginUrl", problem)),
                    () -> {
                        if (!stagingUrls.isSameSite(account.loginUrl(), stagingUrl)) {
                            violations.add(new FieldViolation(
                                    path + ".loginUrl", "must be on the staging site or one of its subdomains"));
                        }
                    });
        }
    }

    private static void checkDepartments(List<DepartmentMappingInput> mappings, List<FieldViolation> violations) {
        Set<Finding.Category> seen = EnumSet.noneOf(Finding.Category.class);
        for (int i = 0; i < mappings.size(); i++) {
            DepartmentMappingInput mapping = mappings.get(i);
            String path = "departmentMappings[" + i + "]";
            if (!REQUIRED_DEPARTMENTS.contains(mapping.category()) || !seen.add(mapping.category())) {
                violations.add(new FieldViolation(path + ".category", "must be a different department on each row"));
            }
            boolean hasLead = mapping.securityLeadZohoUserId() != null && !mapping.securityLeadZohoUserId().isBlank();
            if (mapping.category() == Finding.Category.SECURITY && !hasLead) {
                violations.add(new FieldViolation(path + ".securityLeadZohoUserId", "must not be blank"));
            }
        }
        if (!seen.containsAll(REQUIRED_DEPARTMENTS)) {
            violations.add(new FieldViolation(
                    "departmentMappings", "must cover Design, Frontend, Backend and Security"));
        }
    }
}
