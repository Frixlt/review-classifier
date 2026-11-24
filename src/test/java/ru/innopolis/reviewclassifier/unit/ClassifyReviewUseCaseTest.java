package ru.innopolis.reviewclassifier.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.innopolis.reviewclassifier.application.ClassifyReviewUseCase;
import ru.innopolis.reviewclassifier.domain.exception.ModelInferenceException;
import ru.innopolis.reviewclassifier.domain.exception.ModelNotReadyException;
import ru.innopolis.reviewclassifier.domain.exception.ValidationException;
import ru.innopolis.reviewclassifier.domain.model.AuditEvent;
import ru.innopolis.reviewclassifier.domain.model.ClassificationResult;
import ru.innopolis.reviewclassifier.domain.model.IncidentCard;
import ru.innopolis.reviewclassifier.domain.model.IssueCategory;
import ru.innopolis.reviewclassifier.domain.model.ReviewInput;
import ru.innopolis.reviewclassifier.domain.model.Sentiment;
import ru.innopolis.reviewclassifier.domain.port.AuditLogPort;
import ru.innopolis.reviewclassifier.domain.port.ClockPort;
import ru.innopolis.reviewclassifier.domain.port.MetricsPort;
import ru.innopolis.reviewclassifier.domain.port.ModelWeightsPort;
import ru.innopolis.reviewclassifier.domain.port.QualityAssurancePort;
import ru.innopolis.reviewclassifier.domain.service.CriticalityEvaluator;
import ru.innopolis.reviewclassifier.domain.service.IssueCategoryDetector;
import ru.innopolis.reviewclassifier.domain.service.PiiMasker;
import ru.innopolis.reviewclassifier.domain.service.ReviewValidator;
import ru.innopolis.reviewclassifier.domain.service.SentimentScoringService;
import ru.innopolis.reviewclassifier.domain.service.TextPreprocessor;

class ClassifyReviewUseCaseTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-24T03:00:00Z");

    private ModelWeightsPort modelWeightsPort;
    private QualityAssurancePort qaPort;
    private AuditLogPort auditLogPort;
    private MetricsPort metricsPort;
    private ClockPort clockPort;
    private ClassifyReviewUseCase useCase;

    @BeforeEach
    void setUp() {
        modelWeightsPort = mock(ModelWeightsPort.class);
        qaPort = mock(QualityAssurancePort.class);
        auditLogPort = mock(AuditLogPort.class);
        metricsPort = mock(MetricsPort.class);
        clockPort = mock(ClockPort.class);

        when(modelWeightsPort.isReady()).thenReturn(true);
        when(modelWeightsPort.getWeights()).thenReturn(Map.of("отличный", 2.0, "брак", -2.0, "бракованный", -2.5));
        when(clockPort.now()).thenReturn(FIXED_NOW);
        when(clockPort.nanoTime()).thenReturn(1_000_000L, 6_000_000L);

        useCase = new ClassifyReviewUseCase(
                new ReviewValidator(),
                new PiiMasker(),
                new TextPreprocessor(),
                new SentimentScoringService(),
                new IssueCategoryDetector(),
                new CriticalityEvaluator(),
                modelWeightsPort,
                qaPort,
                auditLogPort,
                metricsPort,
                clockPort);
    }

    @Test
    void throwsModelNotReadyWhenWeightsAreUnloaded() {
        when(modelWeightsPort.isReady()).thenReturn(false);
        ReviewInput input = sampleInput(5, "Отличный товар");

        assertThatThrownBy(() -> useCase.execute(input)).isInstanceOf(ModelNotReadyException.class);
        verify(auditLogPort, never()).logClassification(any());
        verify(qaPort, never()).sendCriticalIncident(any());
    }

    @Test
    void recordsValidationErrorMetricOnInvalidInput() {
        ReviewInput invalid = new ReviewInput(null, "", 0, "", null);

        assertThatThrownBy(() -> useCase.execute(invalid)).isInstanceOf(ValidationException.class);
        verify(metricsPort).recordValidationError();
        verify(auditLogPort, never()).logClassification(any());
    }

    @Test
    void masksPiiAndForwardsCriticalIncidentToQaAndAuditLog() {
        ReviewInput input =
                sampleInput(4, "Меня зовут Иван Петров, +7 (999) 123-45-67, test@mail.ru, товар бракованный!");

        ClassificationResult result = useCase.execute(input);

        assertThat(result.sentiment()).isEqualTo(Sentiment.NEGATIVE);
        assertThat(result.isCritical()).isTrue();
        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.issueCategory()).isEqualTo(IssueCategory.DEFECT);
        assertThat(result.processingTimeMs()).isEqualTo(5L);
        assertThat(result.maskedText())
                .contains("[NAME]")
                .contains("[PHONE]")
                .contains("[EMAIL]")
                .doesNotContain("Иван")
                .doesNotContain("999")
                .doesNotContain("test@mail.ru");

        ArgumentCaptor<IncidentCard> cardCaptor = ArgumentCaptor.forClass(IncidentCard.class);
        verify(qaPort).sendCriticalIncident(cardCaptor.capture());
        assertThat(cardCaptor.getValue().maskedText()).doesNotContain("999").contains("[PHONE]");

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogPort).logClassification(auditCaptor.capture());
        assertThat(auditCaptor.getValue().sentiment()).isEqualTo("negative");
        assertThat(auditCaptor.getValue().maskedText()).doesNotContain("test@mail.ru");

        verify(metricsPort).recordClassification(result);
    }

    @Test
    void skipsQaOnNonCriticalPositiveReview() {
        ReviewInput input = sampleInput(5, "Отличный товар, спасибо");

        ClassificationResult result = useCase.execute(input);

        assertThat(result.sentiment()).isEqualTo(Sentiment.POSITIVE);
        assertThat(result.isCritical()).isFalse();
        verify(qaPort, never()).sendCriticalIncident(any());
        verify(auditLogPort).logClassification(any());
        verify(metricsPort).recordClassification(result);
    }

    @Test
    void activatesRuntimeFallbackWithNullSentimentWhenModelFails() {
        when(modelWeightsPort.getWeights())
                .thenThrow(new ModelInferenceException("corrupt runtime weights", new IllegalStateException("boom")));

        ClassificationResult lowRatingResult = useCase.execute(sampleInput(2, "Не очень"));
        assertThat(lowRatingResult.fallbackUsed()).isTrue();
        assertThat(lowRatingResult.sentiment()).isNull();
        assertThat(lowRatingResult.isCritical()).isTrue();

        when(clockPort.nanoTime()).thenReturn(1_000_000L, 3_000_000L);
        ClassificationResult highRatingResult = useCase.execute(sampleInput(4, "Товар бракованный"));
        assertThat(highRatingResult.fallbackUsed()).isTrue();
        assertThat(highRatingResult.sentiment()).isNull();
        assertThat(highRatingResult.isCritical()).isFalse();

        verify(qaPort, org.mockito.Mockito.times(1)).sendCriticalIncident(any());
        verify(metricsPort, org.mockito.Mockito.times(2)).recordFallback();
    }

    @Test
    void activatesRuntimeFallbackWhenModelInferenceExceeds500MsTimeout() {
        ClassifyReviewUseCase timedUseCase = new ClassifyReviewUseCase(
                new ReviewValidator(),
                new PiiMasker(),
                new TextPreprocessor(),
                new SentimentScoringService(),
                new IssueCategoryDetector(),
                new CriticalityEvaluator(),
                modelWeightsPort,
                qaPort,
                auditLogPort,
                metricsPort,
                clockPort,
                40L);

        when(modelWeightsPort.getWeights()).thenAnswer(inv -> {
            Thread.sleep(200L);
            return Map.of("отличный", 2.0);
        });
        when(clockPort.nanoTime()).thenReturn(1_000_000L, 45_000_000L);

        ClassificationResult result = timedUseCase.execute(sampleInput(4, "Товар бракованный"));

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.sentiment()).isNull();
        assertThat(result.isCritical()).isFalse();
        verify(metricsPort).recordFallback();
        verify(qaPort, never()).sendCriticalIncident(any());
    }

    private static ReviewInput sampleInput(int rating, String text) {
        return new ReviewInput(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "ORD-777",
                rating,
                text,
                Instant.parse("2026-11-24T02:55:00Z"));
    }
}
