package ru.innopolis.reviewclassifier.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ru.innopolis.reviewclassifier.domain.service.TextPreprocessor;

class TextPreprocessorTest {

    private final TextPreprocessor preprocessor = new TextPreprocessor();

    @Test
    void returnsEmptyListForNullOrBlank() {
        assertThat(preprocessor.tokenize(null)).isEmpty();
        assertThat(preprocessor.tokenize("   !!!  ---  ")).isEmpty();
    }

    @Test
    void normalizesCaseYoToYeAndStripsPunctuation() {
        var tokens = preprocessor.tokenize("Тяжёлый, но ОТЛИЧНЫЙ утюг — всё работает на 5+!");

        assertThat(tokens).containsExactly("тяжелый", "но", "отличный", "утюг", "все", "работает", "на", "5");
    }
}
