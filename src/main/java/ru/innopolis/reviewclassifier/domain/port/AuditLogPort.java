package ru.innopolis.reviewclassifier.domain.port;

import ru.innopolis.reviewclassifier.domain.model.AuditEvent;

/** Outbound port for structured PII-safe audit logging. */
public interface AuditLogPort {
    void logClassification(AuditEvent event);
}
