package ru.innopolis.reviewclassifier.domain.model;

import java.util.UUID;

/** Result of review classification. Note: sentiment is null iff fallbackUsed is true (DEC-05). */
public record ClassificationResult(
        UUID reviewId,
        String orderId,
        int rating,
        Sentiment sentiment,
        boolean isCritical,
        boolean fallbackUsed,
        IssueCategory issueCategory,
        String maskedText,
        long processingTimeMs) {}
