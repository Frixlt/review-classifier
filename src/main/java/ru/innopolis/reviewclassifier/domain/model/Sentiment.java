package ru.innopolis.reviewclassifier.domain.model;

/** Binary sentiment classification values matching the prototype contract. */
public enum Sentiment {
    POSITIVE("positive"),
    NEGATIVE("negative");

    private final String label;

    Sentiment(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
