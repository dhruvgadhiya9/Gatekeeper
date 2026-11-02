package com.gatekeeper.qaplatform.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuditEventRepository events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(AuditEventRepository events, ObjectMapper objectMapper, Clock clock) {
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Uses its own transaction, so the record survives if the caller's transaction rolls back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, String action, String target, Map<String, ?> details) {
        events.save(new AuditEvent(actor, action, target, toJson(details), clock.instant()));
    }

    private String toJson(Map<String, ?> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit details are not serializable", e);
        }
    }
}
