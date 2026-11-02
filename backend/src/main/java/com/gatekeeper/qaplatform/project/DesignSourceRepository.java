package com.gatekeeper.qaplatform.project;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DesignSourceRepository extends JpaRepository<DesignSource, UUID> {

    List<DesignSource> findByProjectId(UUID projectId);
}
