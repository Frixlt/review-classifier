package ru.innopolis.reviewclassifier.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.innopolis.reviewclassifier.domain.service.PiiMasker;

class PiiMaskerTest {

    private final PiiMasker masker = new PiiMasker();

    @Test
    void returnsEmptyStringWhenInputIsNull() {
        assertThat(masker.mask(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "+7 (999) 123-45-67",
                "89991234567",
                "7-999-123-45-67",
                "+7 916 555 00 11",
                "+7\u00A0999\u00A0123\u00A045\u00A067",
                "8.999.123.45.67"
            })
    void masksRussianPhoneFormatsIncludingDotsAndNonBreakingSpaces(String phone) {
        String masked = masker.mask("Позвоните по номеру " + phone + " срочно");

        assertThat(masked).contains("[PHONE]").doesNotContain(phone);
    }

    @Test
    void masksUnicodeAndAsciiEmailsAndNamesBeforeContacts() {
        String raw = "Иван Петров, +79991112233, user.name+1@example.com — товар сломался";
        String masked = masker.mask(raw);

        assertThat(masked)
                .contains("[NAME]")
                .contains("[PHONE]")
                .contains("[EMAIL]")
                .doesNotContain("Иван")
                .doesNotContain("Петров")
                .doesNotContain("9991112233")
                .doesNotContain("example.com");

        String idnEmail = masker.mask("покупатель@почта.рф товар бракованный");
        assertThat(idnEmail).isEqualTo("[EMAIL] товар бракованный");
    }

    @Test
    void masksFullPatronymicFioSurnamesDeclinedNamesAndLatinColonNames() {
        assertThat(masker.mask("Владислав Борисович Сидоров сообщил о браке")).isEqualTo("[NAME] сообщил о браке");
        assertThat(masker.mask("Иван Петров пожаловался на брак")).isEqualTo("[NAME] пожаловался на брак");
        assertThat(masker.mask("Иван\nПетров\nжалоба на брак")).isEqualTo("[NAME]\nжалоба на брак");
        assertThat(masker.mask("Ответьте Сергею и Дмитрию о браке")).isEqualTo("Ответьте [NAME] и [NAME] о браке");
        assertThat(masker.mask("John Smith: товар бракованный")).isEqualTo("[NAME]: товар бракованный");
        assertThat(masker.mask("Сидоров И.П. жалуется на поломку")).isEqualTo("[NAME] жалуется на поломку");
    }

    @Test
    void masksLowercaseCueAllCapsRussianAndUncolonizedLatinNames() {
        assertThat(masker.mask("меня зовут иван петров, товар бракованный"))
                .isEqualTo("меня зовут [NAME], товар бракованный");
        assertThat(masker.mask("ИВАН ПЕТРОВ сообщил о браке")).isEqualTo("[NAME] сообщил о браке");
        assertThat(masker.mask("John Smith сообщил о браке")).isEqualTo("[NAME] сообщил о браке");
        assertThat(masker.mask("JOHN SMITH сообщил о браке")).isEqualTo("[NAME] сообщил о браке");
        assertThat(masker.mask("ВЛАДИСЛАВ БОРИСОВИЧ СИДОРОВ сообщил о браке")).isEqualTo("[NAME] сообщил о браке");
        assertThat(masker.mask("меня зовут анна товар бракованный")).isEqualTo("меня зовут [NAME] товар бракованный");
    }

    @Test
    void preservesDefectKeywordsBrandsAndLowercaseCommonNounsAfterNames() {
        String greetingWithDefect = masker.mask("Меня зовут Анна товар бракованный");
        assertThat(greetingWithDefect)
                .isEqualTo("Меня зовут [NAME] товар бракованный")
                .contains("бракованный");

        String lowercaseRoman = masker.mask("Мне не до романа сейчас");
        assertThat(lowercaseRoman).isEqualTo("Мне не до романа сейчас");

        String brandsAndShoutedDefect = masker.mask("БРАК!!! Купил Samsung Galaxy и Apple Watch");
        assertThat(brandsAndShoutedDefect).isEqualTo("БРАК!!! Купил Samsung Galaxy и Apple Watch");
    }

    @Test
    void masksGreetingAndSignatureNamesAndPreservesCleanProductText() {
        String raw = "Меня зовут Елена Соколова. Доставка плохая. С уважением, Дмитрий Волков.";
        String masked = masker.mask(raw);

        assertThat(masked)
                .contains("Меня зовут [NAME]")
                .contains("С уважением, [NAME]")
                .doesNotContain("Елена")
                .doesNotContain("Соколова")
                .doesNotContain("Дмитрий")
                .doesNotContain("Волков");

        String clean = "Отличный ноутбук, доставка быстрая, экран яркий 16 дюймов!";
        assertThat(masker.mask(clean)).isEqualTo(clean);
    }
}
