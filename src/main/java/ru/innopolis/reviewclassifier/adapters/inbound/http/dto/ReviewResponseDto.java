package ru.innopolis.reviewclassifier.adapters.inbound.http.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;
import ru.innopolis.reviewclassifier.domain.model.ClassificationResult;

/** Outbound JSON response for POST /api/v1/reviews/classify. */
public record ReviewResponseDto(
        UUID reviewId,
        String orderId,
        int rating,
        String sentiment,
        @JsonProperty("isCritical") boolean isCritical,
        boolean fallbackUsed,
        String issueCategory,
        String maskedText,
        long processingTimeMs) {

    public static ReviewResponseDto fromDomain(ClassificationResult result) {
        return new ReviewResponseDto(
                result.reviewId(),
                result.orderId(),
                result.rating(),
                result.sentiment() == null ? null : result.sentiment().label(),
                result.isCritical(),
                result.fallbackUsed(),
                result.issueCategory().name(),
                result.maskedText(),
                result.processingTimeMs());
    }
}
