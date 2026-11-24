package ru.innopolis.reviewclassifier.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Critical incident payload forwarded to the Quality Assurance contour without raw PII (FR-10, FR-11). */
public record IncidentCard(
        UUID reviewId,
        String orderId,
        int rating,
        Sentiment sentiment,
        boolean fallbackUsed,
        IssueCategory issueCategory,
        String maskedText,
        Instant createdAt) {}
