package ru.innopolis.reviewclassifier.domain.service;

import ru.innopolis.reviewclassifier.domain.model.IssueCategory;
import ru.innopolis.reviewclassifier.domain.model.Sentiment;

/**
 * Evaluates review criticality (FR-08, FR-10, DEC-01, DEC-02):
 * 1-2 stars are unconditionally critical; 3-5 stars are critical iff negative AND issue marker present.
 */
public final class CriticalityEvaluator {
    private static final int UNCONDITIONAL_CRITICAL_MAX_RATING = 2;

    public boolean isCritical(int rating, Sentiment sentiment, IssueCategory category) {
        return rating <= UNCONDITIONAL_CRITICAL_MAX_RATING
                || (sentiment == Sentiment.NEGATIVE && category != IssueCategory.OTHER);
    }
}
