package com.gatekeeper.qaplatform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only record of a security-relevant action. */
@Entity
@Immutable
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String actor;
    private String action;
    private String target;

    @JdbcTypeCode(SqlTypes.JSON)
    private String detailsJson;

    @Column(name = "occurred_at")
    private Instant at;

    protected AuditEvent() {}

    public AuditEvent(String actor, String action, String target, String detailsJson, Instant at) {
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.detailsJson = detailsJson;
        this.at = at;
    }

    public UUID getId() {
        return id;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getTarget() {
        return target;
    }

    public String getDetailsJson() {
        return detailsJson;
    }

    public Instant getAt() {
        return at;
    }
}
