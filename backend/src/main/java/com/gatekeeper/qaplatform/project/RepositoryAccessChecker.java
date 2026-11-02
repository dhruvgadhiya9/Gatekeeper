package com.gatekeeper.qaplatform.project;

import com.gatekeeper.qaplatform.common.error.FieldViolation;
import com.gatekeeper.qaplatform.common.error.RequestValidationException;
import com.gatekeeper.qaplatform.integration.github.GitHubClient;
import com.gatekeeper.qaplatform.integration.github.GitHubModels.Repository;
import com.gatekeeper.qaplatform.project.dto.ProjectRequest;
import com.gatekeeper.qaplatform.project.dto.ProjectRequest.RepositoryInput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Once the GitHub App is set up, every repository must be one that the App can reach. Called before
 * the database transaction starts, so a slow GitHub can't hold a connection open.
 */
@Component
public class RepositoryAccessChecker {

    private final GitHubClient github;

    public RepositoryAccessChecker(GitHubClient github) {
        this.github = github;
    }

    public void check(ProjectRequest request) {
        if (!github.isConfigured()) {
            return;
        }
        Map<Long, Set<String>> reachable = new HashMap<>();
        List<FieldViolation> violations = new ArrayList<>();
        for (int i = 0; i < request.repositories().size(); i++) {
            RepositoryInput repository = request.repositories().get(i);
            String path = "repositories[" + i + "]";
            if (repository.installationId() == null) {
                violations.add(new FieldViolation(path + ".installationId", "must be chosen from the connected GitHub accounts"));
            } else if (!reachable
                    .computeIfAbsent(repository.installationId(), this::repositoryNames)
                    .contains(repository.repoFullName().toLowerCase(Locale.ROOT))) {
                violations.add(new FieldViolation(path + ".repoFullName", "isn't available to the connected GitHub App"));
            }
        }
        if (!violations.isEmpty()) {
            throw new RequestValidationException(violations);
        }
    }

    private Set<String> repositoryNames(long installationId) {
        return github.listRepositories(installationId).stream()
                .map(Repository::fullName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }
}
