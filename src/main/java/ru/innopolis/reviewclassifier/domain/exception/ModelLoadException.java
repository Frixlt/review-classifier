package ru.innopolis.reviewclassifier.domain.exception;

import java.io.Serial;

/** Raised when loading or validating the sentiment weights file fails at cold start (FR-14). */
public final class ModelLoadException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ModelLoadException(String message) {
        super(message);
    }

    public ModelLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
