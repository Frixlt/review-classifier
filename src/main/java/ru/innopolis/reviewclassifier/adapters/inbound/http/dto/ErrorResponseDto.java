package ru.innopolis.reviewclassifier.adapters.inbound.http.dto;

import java.time.Instant;
import java.util.Map;

/** Structured JSON error payload returned on 4xx/5xx responses. */
public record ErrorResponseDto(
        Instant timestamp, int status, String error, String message, Map<String, String> details) {

    public ErrorResponseDto {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
