package com.gatekeeper.qaplatform.routing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ZohoFieldMappingRepository extends JpaRepository<ZohoFieldMapping, UUID> {

    List<ZohoFieldMapping> findByPortalId(String portalId);

    Optional<ZohoFieldMapping> findByPortalIdAndKindAndGatekeeperKey(
            String portalId, ZohoFieldMapping.Kind kind, String gatekeeperKey);

    @Modifying
    @Query("delete from ZohoFieldMapping m where m.portalId = :portalId")
    void deleteAllForPortal(String portalId);
}
