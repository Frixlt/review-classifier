package ru.innopolis.reviewclassifier.domain.service;

import java.util.List;
import java.util.Map;
import ru.innopolis.reviewclassifier.domain.model.Sentiment;

/** Computes sentiment score from token weights and returns binary sentiment (FR-06, FR-07, DEC-02). */
public final class SentimentScoringService {

    public double calculateScore(List<String> tokens, Map<String, Double> weights) {
        if (tokens == null || tokens.isEmpty() || weights == null || weights.isEmpty()) {
            return 0.0;
        }
        double score = 0.0;
        for (String token : tokens) {
            Double weight = weights.get(token);
            if (weight != null) {
                score += weight;
            }
        }
        return score;
    }

    public Sentiment classify(List<String> tokens, Map<String, Double> weights) {
        double score = calculateScore(tokens, weights);
        return score > 0.0 ? Sentiment.POSITIVE : Sentiment.NEGATIVE;
    }
}
