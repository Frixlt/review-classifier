package ru.innopolis.reviewclassifier.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import ru.innopolis.reviewclassifier.adapters.outbound.JsonMapperFactory;
import ru.innopolis.reviewclassifier.adapters.outbound.JsonStdoutAuditLogger;
import ru.innopolis.reviewclassifier.domain.model.AuditEvent;

class JsonStdoutAuditLoggerTest {

    private final ObjectMapper objectMapper = JsonMapperFactory.create();

    @Test
    void serializesStructuredSingleLineJsonWithAllRequiredFields() throws Exception {
        Logger targetLogger = mock(Logger.class);
        JsonStdoutAuditLogger auditLogger = new JsonStdoutAuditLogger(objectMapper, targetLogger);

        UUID id = UUID.fromString("33333333-3333-3333-3333-333333333333");
        AuditEvent event = new AuditEvent(
                Instant.parse("2026-11-24T03:15:00Z"),
                id,
                "ORD-99",
                4,
                "negative",
                true,
                false,
                12L,
                "[NAME], [PHONE], брак");

        auditLogger.logClassification(event);

        ArgumentCaptor<String> lineCaptor = ArgumentCaptor.forClass(String.class);
        verify(targetLogger).info(lineCaptor.capture());

        String rawLine = lineCaptor.getValue();
        assertThat(rawLine).doesNotContain("\n").doesNotContain("\r");

        JsonNode json = objectMapper.readTree(rawLine);
        assertThat(json.get("reviewId").asText()).isEqualTo(id.toString());
        assertThat(json.get("orderId").asText()).isEqualTo("ORD-99");
        assertThat(json.get("rating").asInt()).isEqualTo(4);
        assertThat(json.get("sentiment").asText()).isEqualTo("negative");
        assertThat(json.get("isCritical").asBoolean()).isTrue();
        assertThat(json.get("fallbackUsed").asBoolean()).isFalse();
        assertThat(json.get("processingTimeMs").asLong()).isEqualTo(12L);
        assertThat(json.get("maskedText").asText()).isEqualTo("[NAME], [PHONE], брак");
    }

    @Test
    void writesNullSentimentCleanlyDuringFallback() throws Exception {
        Logger targetLogger = mock(Logger.class);
        JsonStdoutAuditLogger auditLogger = new JsonStdoutAuditLogger(objectMapper, targetLogger);

        AuditEvent event = new AuditEvent(
                Instant.parse("2026-11-24T03:20:00Z"),
                UUID.randomUUID(),
                "ORD-100",
                1,
                null,
                true,
                true,
                2L,
                "сломался");

        auditLogger.logClassification(event);

        ArgumentCaptor<String> lineCaptor = ArgumentCaptor.forClass(String.class);
        verify(targetLogger).info(lineCaptor.capture());

        JsonNode json = objectMapper.readTree(lineCaptor.getValue());
        assertThat(json.get("sentiment").isNull()).isTrue();
        assertThat(json.get("fallbackUsed").asBoolean()).isTrue();
        assertThat(json.get("isCritical").asBoolean()).isTrue();
    }
}
