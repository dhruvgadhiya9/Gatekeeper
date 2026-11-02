package com.gatekeeper.qaplatform.routing;

import com.gatekeeper.qaplatform.finding.Finding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DepartmentMappingRepository extends JpaRepository<DepartmentMapping, UUID> {

    List<DepartmentMapping> findByProjectId(UUID projectId);

    Optional<DepartmentMapping> findByProjectIdAndCategory(UUID projectId, Finding.Category category);

    @Modifying
    @Query("delete from DepartmentMapping d where d.projectId = :projectId")
    void deleteAllForProject(UUID projectId);
}
