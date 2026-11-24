package ru.innopolis.reviewclassifier.adapters.outbound;

import java.time.Instant;
import java.util.UUID;
import ru.innopolis.reviewclassifier.domain.model.IncidentCard;

/**
 * Outbound JSON wire and outbox DTO for Quality Assurance incidents, serializing Sentiment
 * using lowercase {@code "positive"}/{@code "negative"}/{@code null} per FR-11 (M3).
 */
public record IncidentCardDto(
        UUID reviewId,
        String orderId,
        int rating,
        String sentiment,
        boolean fallbackUsed,
        String issueCategory,
        String maskedText,
        Instant createdAt) {

    public static IncidentCardDto fromDomain(IncidentCard card) {
        return new IncidentCardDto(
                card.reviewId(),
                card.orderId(),
                card.rating(),
                card.sentiment() == null ? null : card.sentiment().label(),
                card.fallbackUsed(),
                card.issueCategory().name(),
                card.maskedText(),
                card.createdAt());
    }
}
