package com.gatekeeper.qaplatform.project;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DesignUploadRepository extends JpaRepository<DesignUpload, UUID> {

    List<DesignUpload> findByDesignSourceIdOrderByFileNameAsc(UUID designSourceId);

    long countByDesignSourceId(UUID designSourceId);
}
