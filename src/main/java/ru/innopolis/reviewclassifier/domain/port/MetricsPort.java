package ru.innopolis.reviewclassifier.domain.port;

import ru.innopolis.reviewclassifier.domain.model.ClassificationResult;

/** Outbound port for thread-safe technical and business metrics recording. */
public interface MetricsPort {
    void recordClassification(ClassificationResult result);

    void recordValidationError();

    void recordFallback();

    void recordSlaBreach();
}
