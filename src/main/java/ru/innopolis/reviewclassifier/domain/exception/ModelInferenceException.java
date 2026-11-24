package ru.innopolis.reviewclassifier.domain.exception;

import java.io.Serial;

/** Raised when model weight evaluation fails or times out at runtime, triggering Runtime Fallback. */
public final class ModelInferenceException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ModelInferenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
