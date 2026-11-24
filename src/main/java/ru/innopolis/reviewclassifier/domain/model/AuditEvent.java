package ru.innopolis.reviewclassifier.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Structured audit record emitted for every classified review without raw PII (FR-15, DEC-04). */
public record AuditEvent(
        Instant timestamp,
        UUID reviewId,
        String orderId,
        int rating,
        String sentiment,
        boolean isCritical,
        boolean fallbackUsed,
        long processingTimeMs,
        String maskedText) {}
