package ru.innopolis.reviewclassifier.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.innopolis.reviewclassifier.domain.exception.ValidationException;
import ru.innopolis.reviewclassifier.domain.model.ReviewInput;
import ru.innopolis.reviewclassifier.domain.service.ReviewValidator;

class ReviewValidatorTest {

    private final ReviewValidator validator = new ReviewValidator();
    private final UUID validId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final Instant validTime = Instant.parse("2026-11-24T01:00:00Z");

    @Test
    void acceptsValidBoundaryInputs() {
        ReviewInput minRatingSingleChar = new ReviewInput(validId, "ORD-1", 1, "x", validTime);
        ReviewInput maxRatingMaxText =
                new ReviewInput(validId, "ORD-2", 5, "a".repeat(ReviewValidator.MAX_TEXT_LENGTH), validTime);

        assertThatCode(() -> validator.validate(minRatingSingleChar)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(maxRatingMaxText)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNullInput() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex ->
                        assertThat(((ValidationException) ex).getFieldErrors()).containsKey("body"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 6, 99})
    void rejectsOutOfRangeRating(int invalidRating) {
        ReviewInput input = new ReviewInput(validId, "ORD-1", invalidRating, "Нормальный товар", validTime);

        assertThatThrownBy(() -> validator.validate(input))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex ->
                        assertThat(((ValidationException) ex).getFieldErrors()).containsKey("rating"));
    }

    @Test
    void rejectsMissingAndTooLongFieldsWithAllErrorsCollected() {
        ReviewInput invalid = new ReviewInput(null, "   ", 0, "a".repeat(5001), null);

        assertThatThrownBy(() -> validator.validate(invalid))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    var errors = ((ValidationException) ex).getFieldErrors();
                    assertThat(errors).containsKeys("reviewId", "orderId", "rating", "text", "createdAt");
                });
    }

    @Test
    void rejectsBlankText() {
        ReviewInput input = new ReviewInput(validId, "ORD-1", 3, "   \t\n ", validTime);

        assertThatThrownBy(() -> validator.validate(input))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex ->
                        assertThat(((ValidationException) ex).getFieldErrors()).containsKey("text"));
    }
}
