package ru.innopolis.reviewclassifier.domain.service;

import java.util.List;
import ru.innopolis.reviewclassifier.domain.model.IssueCategory;

/** Detects defect and delivery-issue stem markers in normalized review tokens (FR-09). */
public final class IssueCategoryDetector {
    private static final List<String> DEFECT_PREFIXES = List.of("брак", "слом", "полом", "дефект", "неисправ");

    private static final List<String> DELIVERY_SINGLE_PREFIXES = List.of("опозда", "задерж");

    private static final List<String> NEGATED_DELIVERY_PREFIXES = List.of("достав", "привез");

    public IssueCategory detect(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return IssueCategory.OTHER;
        }
        if (hasDefectMarker(tokens)) {
            return IssueCategory.DEFECT;
        }
        if (hasDeliveryMarker(tokens)) {
            return IssueCategory.DELIVERY_ISSUE;
        }
        return IssueCategory.OTHER;
    }

    private boolean hasDefectMarker(List<String> tokens) {
        for (String token : tokens) {
            if (startsWithAny(token, DEFECT_PREFIXES)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDeliveryMarker(List<String> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (startsWithAny(token, DELIVERY_SINGLE_PREFIXES)) {
                return true;
            }
            if ("не".equals(token)
                    && i + 1 < tokens.size()
                    && startsWithAny(tokens.get(i + 1), NEGATED_DELIVERY_PREFIXES)) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWithAny(String token, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (token.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
