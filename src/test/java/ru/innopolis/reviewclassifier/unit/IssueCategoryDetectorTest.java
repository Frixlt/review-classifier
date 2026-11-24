package ru.innopolis.reviewclassifier.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.innopolis.reviewclassifier.domain.model.IssueCategory;
import ru.innopolis.reviewclassifier.domain.service.IssueCategoryDetector;
import ru.innopolis.reviewclassifier.domain.service.TextPreprocessor;

class IssueCategoryDetectorTest {

    private final IssueCategoryDetector detector = new IssueCategoryDetector();
    private final TextPreprocessor preprocessor = new TextPreprocessor();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Пришел явный брак корпуса",
                "Утюг сломался на второй день",
                "Кнопка поломалась сразу",
                "Случилась поломка на второй день",
                "После поломки оформил возврат",
                "Пришел поломанный корпус",
                "Обнаружился серьезный дефект экрана",
                "Товар полностью неисправен"
            })
    void detectsDefectCategoryFromRussianStems(String text) {
        IssueCategory category = detector.detect(preprocessor.tokenize(text));

        assertThat(category).isEqualTo(IssueCategory.DEFECT);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Курьер опоздал на три часа",
                "Большая задержка отправки со склада",
                "Заказ до сих пор не доставили",
                "Товар вообще не привезли"
            })
    void detectsDeliveryIssueCategory(String text) {
        IssueCategory category = detector.detect(preprocessor.tokenize(text));

        assertThat(category).isEqualTo(IssueCategory.DELIVERY_ISSUE);
    }

    @Test
    void returnsOtherForNeutralOrDeliveredOnTimeTextAndEmptyTokens() {
        assertThat(detector.detect(null)).isEqualTo(IssueCategory.OTHER);
        assertThat(detector.detect(List.of())).isEqualTo(IssueCategory.OTHER);
        assertThat(detector.detect(preprocessor.tokenize("Доставили быстро, цвет не подошел")))
                .isEqualTo(IssueCategory.OTHER);
    }

    @Test
    void prioritizesDefectWhenBothDefectAndDeliveryMarkersPresent() {
        IssueCategory category = detector.detect(preprocessor.tokenize("Курьер опоздал и привез бракованный товар"));

        assertThat(category).isEqualTo(IssueCategory.DEFECT);
    }
}
