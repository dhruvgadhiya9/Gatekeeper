package com.gatekeeper.qaplatform.integration.zoho;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZohoConnectionRepository extends JpaRepository<ZohoConnection, UUID> {

    Optional<ZohoConnection> findFirstByOrderByCreatedAtDesc();
}
