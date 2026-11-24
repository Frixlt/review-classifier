package ru.innopolis.reviewclassifier.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Immutable domain input representing an incoming customer review. */
public record ReviewInput(UUID reviewId, String orderId, int rating, String text, Instant createdAt) {}
