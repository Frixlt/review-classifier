package ru.innopolis.reviewclassifier.adapters.inbound.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import ru.innopolis.reviewclassifier.adapters.inbound.http.dto.ErrorResponseDto;

/** Shared low-level HTTP request reading and response writing helpers. */
public final class HttpResponseSupport {
    private static final int MAX_BODY_BYTES = 65_536;
    private static final int HTTP_NOT_FOUND = 404;

    private HttpResponseSupport() {}

    public static String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            byte[] bytes = is.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new PayloadTooLargeException("Request payload exceeds " + MAX_BODY_BYTES + " bytes");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    public static void sendJson(HttpExchange exchange, ObjectMapper mapper, int statusCode, Object body)
            throws IOException {
        byte[] jsonBytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, jsonBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(jsonBytes);
        }
    }

    public static void sendText(HttpExchange exchange, int statusCode, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void sendNotFound(HttpExchange exchange, ObjectMapper mapper, Instant timestamp) throws IOException {
        ErrorResponseDto payload =
                new ErrorResponseDto(timestamp, HTTP_NOT_FOUND, "Not Found", "Endpoint does not exist", Map.of());
        sendJson(exchange, mapper, HTTP_NOT_FOUND, payload);
    }

    /** Internal marker exception for oversized HTTP payloads (HTTP 400). */
    public static final class PayloadTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        public PayloadTooLargeException(String message) {
            super(message);
        }
    }
}
