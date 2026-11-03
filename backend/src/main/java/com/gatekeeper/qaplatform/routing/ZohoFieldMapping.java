package com.gatekeeper.qaplatform.routing;

import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** The ID of the Zoho option matching a Gatekeeper severity or category, per portal. */
@Entity
@Table(name = "zoho_field_mapping")
public class ZohoFieldMapping extends BaseEntity {

    public enum Kind {
        SEVERITY,
        CLASSIFICATION
    }

    private String portalId;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    private String gatekeeperKey;
    private String zohoOptionId;

    protected ZohoFieldMapping() {}

    public ZohoFieldMapping(String portalId, Kind kind, String gatekeeperKey, String zohoOptionId) {
        this.portalId = portalId;
        this.kind = kind;
        this.gatekeeperKey = gatekeeperKey;
        this.zohoOptionId = zohoOptionId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getGatekeeperKey() {
        return gatekeeperKey;
    }

    public String getZohoOptionId() {
        return zohoOptionId;
    }
}
