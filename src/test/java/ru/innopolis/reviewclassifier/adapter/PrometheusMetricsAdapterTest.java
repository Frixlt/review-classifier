package ru.innopolis.reviewclassifier.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.innopolis.reviewclassifier.adapters.outbound.PrometheusMetricsAdapter;
import ru.innopolis.reviewclassifier.domain.model.ClassificationResult;
import ru.innopolis.reviewclassifier.domain.model.IssueCategory;
import ru.innopolis.reviewclassifier.domain.model.Sentiment;

class PrometheusMetricsAdapterTest {

    @Test
    void tracksCountersHistogramsAndOutboxGaugesInPrometheusFormat() {
        PrometheusMetricsAdapter metrics = new PrometheusMetricsAdapter();

        metrics.recordClassification(new ClassificationResult(
                UUID.randomUUID(), "O1", 5, Sentiment.POSITIVE, false, false, IssueCategory.OTHER, "ок", 10L));
        metrics.recordClassification(new ClassificationResult(
                UUID.randomUUID(), "O2", 4, Sentiment.NEGATIVE, true, false, IssueCategory.DEFECT, "брак", 15L));
        metrics.recordFallback();
        metrics.recordValidationError();
        metrics.recordSlaBreach();
        metrics.recordHttpRequestDurationMs(42L);
        metrics.recordQaDeliveryLatencySeconds(12.5);
        metrics.updateOutboxStats(3, 620L);

        assertThat(metrics.getReviewsTotal()).isEqualTo(2L);
        assertThat(metrics.getCriticalReviewsTotal()).isEqualTo(1L);
        assertThat(metrics.getFallbackTotal()).isEqualTo(1L);
        assertThat(metrics.getSlaBreachTotal()).isEqualTo(1L);
        assertThat(metrics.getQaOutboxPending()).isEqualTo(3);
        assertThat(metrics.getQaOutboxOldestAgeSeconds()).isEqualTo(620L);

        String exported = metrics.exportPrometheus();
        assertThat(exported)
                .contains("reviews_total 2")
                .contains("reviews_positive_total 1")
                .contains("reviews_negative_total 1")
                .contains("critical_reviews_total 1")
                .contains("fallback_total 1")
                .contains("validation_errors_total 1")
                .contains("sla_breach_total 1")
                .contains("classification_processing_ms_sum 25")
                .contains("qa_outbox_pending 3")
                .contains("qa_outbox_oldest_age_seconds 620")
                .contains("http_request_duration_ms_bucket{le=\"50\"} 1")
                .contains("http_request_duration_ms_count 1")
                .contains("qa_delivery_latency_seconds_bucket{le=\"30\"} 1")
                .contains("qa_delivery_latency_seconds_count 1");
    }
}
