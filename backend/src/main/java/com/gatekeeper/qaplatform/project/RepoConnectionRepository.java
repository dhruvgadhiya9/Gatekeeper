package com.gatekeeper.qaplatform.project;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RepoConnectionRepository extends JpaRepository<RepoConnection, UUID> {

    List<RepoConnection> findByProjectId(UUID projectId);

    @Modifying
    @Query("delete from RepoConnection r where r.projectId = :projectId")
    void deleteAllForProject(UUID projectId);
}
