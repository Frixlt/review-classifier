package ru.innopolis.reviewclassifier.adapters.inbound.http.dto;

/** Inbound JSON payload for POST /api/v1/reviews/classify. */
public record ReviewRequestDto(String reviewId, String orderId, Integer rating, String text, String createdAt) {}
