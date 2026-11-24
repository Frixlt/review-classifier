package ru.innopolis.reviewclassifier.domain.exception;

import java.io.Serial;

/** Raised when the service is invoked before the sentiment model is loaded and ready (HTTP 503). */
public final class ModelNotReadyException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ModelNotReadyException(String message) {
        super(message);
    }
}
