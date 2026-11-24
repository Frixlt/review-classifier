package ru.innopolis.reviewclassifier.domain.port;

import java.util.Map;

/** Outbound port providing access to immutable sentiment dictionary weights. */
public interface ModelWeightsPort {
    boolean isReady();

    Map<String, Double> getWeights();
}
