package com.gatekeeper.qaplatform.project;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FeatureSpecRepository extends JpaRepository<FeatureSpec, UUID> {

    List<FeatureSpec> findByProjectIdOrderByTitleAsc(UUID projectId);

    @Modifying
    @Query("delete from FeatureSpec f where f.projectId = :projectId")
    void deleteAllForProject(UUID projectId);
}
