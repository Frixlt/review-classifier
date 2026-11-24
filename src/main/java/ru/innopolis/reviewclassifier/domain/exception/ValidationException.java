package ru.innopolis.reviewclassifier.domain.exception;

import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Raised when incoming review parameters violate contract constraints (HTTP 400). */
public final class ValidationException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final LinkedHashMap<String, String> fieldErrors;

    public ValidationException(String message, Map<String, String> fieldErrors) {
        this(message, fieldErrors, null);
    }

    public ValidationException(String message, Map<String, String> fieldErrors, Throwable cause) {
        super(message, cause);
        this.fieldErrors = fieldErrors == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
        return Collections.unmodifiableMap(fieldErrors);
    }
}
