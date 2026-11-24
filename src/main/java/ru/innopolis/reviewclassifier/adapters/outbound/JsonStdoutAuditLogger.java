package ru.innopolis.reviewclassifier.adapters.outbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.innopolis.reviewclassifier.domain.model.AuditEvent;
import ru.innopolis.reviewclassifier.domain.port.AuditLogPort;

/**
 * Emits single-line JSON audit records via SLF4J AUDIT logger (stdout) instead of blocking FileWriter
 * (FR-15, DEC-04, NFR-07).
 */
public final class JsonStdoutAuditLogger implements AuditLogPort {
    private static final Logger LOG = LoggerFactory.getLogger(JsonStdoutAuditLogger.class);
    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("AUDIT");

    private final ObjectMapper objectMapper;
    private final Logger auditTarget;

    public JsonStdoutAuditLogger(ObjectMapper objectMapper) {
        this(objectMapper, AUDIT_LOG);
    }

    public JsonStdoutAuditLogger(ObjectMapper objectMapper, Logger auditTarget) {
        this.objectMapper = objectMapper;
        this.auditTarget = auditTarget;
    }

    @Override
    public void logClassification(AuditEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            auditTarget.info(json);
        } catch (JsonProcessingException ex) {
            LOG.error("Failed to serialize AuditEvent for reviewId={}", event.reviewId(), ex);
        }
    }
}
