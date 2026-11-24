package ru.innopolis.reviewclassifier.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

/**
 * Orchestrates review validation, PII masking, preprocessing, scoring (with Runtime Fallback on errors
 * or inference timeout), criticality evaluation, QA incident forwarding, structured audit logging, and metrics (UC-01).
 */
public final class ClassifyReviewUseCase {
    public static final long DEFAULT_INFERENCE_TIMEOUT_MS = 500L;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final ExecutorService INFERENCE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ReviewValidator validator;
    private final PiiMasker piiMasker;
    private final TextPreprocessor preprocessor;
    private final SentimentScoringService scoringService;
    private final IssueCategoryDetector issueDetector;
    private final CriticalityEvaluator criticalityEvaluator;
    private final ModelWeightsPort modelWeightsPort;
    private final QualityAssurancePort qualityAssurancePort;
    private final AuditLogPort auditLogPort;
    private final MetricsPort metricsPort;
    private final ClockPort clockPort;
    private final long inferenceTimeoutMs;

    public ClassifyReviewUseCase(
            ReviewValidator validator,
            PiiMasker piiMasker,
            TextPreprocessor preprocessor,
            SentimentScoringService scoringService,
            IssueCategoryDetector issueDetector,
            CriticalityEvaluator criticalityEvaluator,
            ModelWeightsPort modelWeightsPort,
            QualityAssurancePort qualityAssurancePort,
            AuditLogPort auditLogPort,
            MetricsPort metricsPort,
            ClockPort clockPort) {
        this(
                validator,
                piiMasker,
                preprocessor,
                scoringService,
                issueDetector,
                criticalityEvaluator,
                modelWeightsPort,
                qualityAssurancePort,
                auditLogPort,
                metricsPort,
                clockPort,
                DEFAULT_INFERENCE_TIMEOUT_MS);
    }

    public ClassifyReviewUseCase(
            ReviewValidator validator,
            PiiMasker piiMasker,
            TextPreprocessor preprocessor,
            SentimentScoringService scoringService,
            IssueCategoryDetector issueDetector,
            CriticalityEvaluator criticalityEvaluator,
            ModelWeightsPort modelWeightsPort,
            QualityAssurancePort qualityAssurancePort,
            AuditLogPort auditLogPort,
            MetricsPort metricsPort,
            ClockPort clockPort,
            long inferenceTimeoutMs) {
        this.validator = Objects.requireNonNull(validator);
        this.piiMasker = Objects.requireNonNull(piiMasker);
        this.preprocessor = Objects.requireNonNull(preprocessor);
        this.scoringService = Objects.requireNonNull(scoringService);
        this.issueDetector = Objects.requireNonNull(issueDetector);
        this.criticalityEvaluator = Objects.requireNonNull(criticalityEvaluator);
        this.modelWeightsPort = Objects.requireNonNull(modelWeightsPort);
        this.qualityAssurancePort = Objects.requireNonNull(qualityAssurancePort);
        this.auditLogPort = Objects.requireNonNull(auditLogPort);
        this.metricsPort = Objects.requireNonNull(metricsPort);
        this.clockPort = Objects.requireNonNull(clockPort);
        if (inferenceTimeoutMs <= 0L) {
            throw new IllegalArgumentException("inferenceTimeoutMs must be positive");
        }
        this.inferenceTimeoutMs = inferenceTimeoutMs;
    }

    public ClassificationResult execute(ReviewInput input) {
        if (!modelWeightsPort.isReady()) {
            throw new ModelNotReadyException("Model weights are not loaded");
        }
        validateInput(input);

        long startNanos = clockPort.nanoTime();
        String maskedText = piiMasker.mask(input.text());
        List<String> tokens = preprocessor.tokenize(maskedText);
        IssueCategory issueCategory = issueDetector.detect(tokens);

        InferenceOutcome outcome = classifyWithTimeout(tokens);
        boolean isCritical = criticalityEvaluator.isCritical(input.rating(), outcome.sentiment(), issueCategory);

        if (isCritical) {
            IncidentCard card = new IncidentCard(
                    input.reviewId(),
                    input.orderId(),
                    input.rating(),
                    outcome.sentiment(),
                    outcome.fallbackUsed(),
                    issueCategory,
                    maskedText,
                    input.createdAt());
            qualityAssurancePort.sendCriticalIncident(card);
        }

        long processingTimeMs = Math.max(0L, (clockPort.nanoTime() - startNanos) / NANOS_PER_MILLI);
        ClassificationResult result = new ClassificationResult(
                input.reviewId(),
                input.orderId(),
                input.rating(),
                outcome.sentiment(),
                isCritical,
                outcome.fallbackUsed(),
                issueCategory,
                maskedText,
                processingTimeMs);

        String sentimentLabel =
                outcome.sentiment() == null ? null : outcome.sentiment().label();
        auditLogPort.logClassification(new AuditEvent(
                clockPort.now(),
                input.reviewId(),
                input.orderId(),
                input.rating(),
                sentimentLabel,
                isCritical,
                outcome.fallbackUsed(),
                processingTimeMs,
                maskedText));
        metricsPort.recordClassification(result);
        return result;
    }

    private InferenceOutcome classifyWithTimeout(List<String> tokens) {
        Future<Sentiment> future = INFERENCE_EXECUTOR.submit(() -> {
            Map<String, Double> weights = modelWeightsPort.getWeights();
            return scoringService.classify(tokens, weights);
        });
        try {
            return new InferenceOutcome(future.get(inferenceTimeoutMs, TimeUnit.MILLISECONDS), false);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            metricsPort.recordFallback();
            return new InferenceOutcome(null, true);
        } catch (TimeoutException | ExecutionException | RuntimeException ex) {
            future.cancel(true);
            metricsPort.recordFallback();
            return new InferenceOutcome(null, true);
        }
    }

    private void validateInput(ReviewInput input) {
        try {
            validator.validate(input);
        } catch (ValidationException ex) {
            metricsPort.recordValidationError();
            throw ex;
        }
    }

    private record InferenceOutcome(Sentiment sentiment, boolean fallbackUsed) {}
}
