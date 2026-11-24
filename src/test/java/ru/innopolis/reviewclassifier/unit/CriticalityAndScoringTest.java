package ru.innopolis.reviewclassifier.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.innopolis.reviewclassifier.domain.model.IssueCategory;
import ru.innopolis.reviewclassifier.domain.model.Sentiment;
import ru.innopolis.reviewclassifier.domain.service.CriticalityEvaluator;
import ru.innopolis.reviewclassifier.domain.service.SentimentScoringService;

class CriticalityAndScoringTest {

    private final CriticalityEvaluator criticalityEvaluator = new CriticalityEvaluator();
    private final SentimentScoringService scoringService = new SentimentScoringService();

    @ParameterizedTest
    @CsvSource({
        "1, POSITIVE, OTHER, true",
        "2, POSITIVE, OTHER, true",
        "2, , OTHER, true",
        "3, NEGATIVE, DEFECT, true",
        "4, NEGATIVE, DELIVERY_ISSUE, true",
        "5, NEGATIVE, DEFECT, true",
        "3, NEGATIVE, OTHER, false",
        "5, POSITIVE, DEFECT, false",
        "4, POSITIVE, OTHER, false",
        "3, , DEFECT, false"
    })
    void evaluatesCriticalityRulesAccurately(
            int rating, Sentiment sentiment, IssueCategory category, boolean expectedCritical) {
        assertThat(criticalityEvaluator.isCritical(rating, sentiment, category)).isEqualTo(expectedCritical);
    }

    @Test
    void scoresPositiveAndNegativeAndTreatsZeroAsNegativePerDec02() {
        Map<String, Double> weights = Map.of("отличный", 2.0, "ужасный", -2.5, "норм", 0.0);

        assertThat(scoringService.classify(List.of("отличный", "товар"), weights))
                .isEqualTo(Sentiment.POSITIVE);
        assertThat(scoringService.classify(List.of("ужасный", "товар"), weights))
                .isEqualTo(Sentiment.NEGATIVE);
        assertThat(scoringService.classify(List.of("отличный", "ужасный"), weights))
                .isEqualTo(Sentiment.NEGATIVE);
        assertThat(scoringService.classify(List.of("неизвестное", "слово"), weights))
                .isEqualTo(Sentiment.NEGATIVE);
        assertThat(scoringService.classify(null, weights)).isEqualTo(Sentiment.NEGATIVE);
    }
}
