package com.gatekeeper.qaplatform.project;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepoConnectionRepository extends JpaRepository<RepoConnection, UUID> {

    List<RepoConnection> findByProjectId(UUID projectId);
}
