package ru.innopolis.reviewclassifier.domain.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Masks personal identifiable information (phones, emails, customer names) before logging,
 * scoring, or forwarding externally (FR-03, FR-04, NFR-04, 152-FZ).
 */
public final class PiiMasker {
    private static final String EMAIL_TOKEN = "[EMAIL]";
    private static final String PHONE_TOKEN = "[PHONE]";
    private static final String NAME_TOKEN = "[NAME]";
    private static final Locale RU_LOCALE = Locale.forLanguageTag("ru");

    private static final String NON_SURNAME_GUARD =
            "(?!(?iu:товар|заказ|брак|слом|полом|дефект|неисправ|достав|привез|опозда|задерж|курьер|магазин|сервис))";

    private static final String CUE_LOOKBEHIND =
            "(?u)(?<=(?i:меня\\s{1,5}зовут|с\\s{1,5}уважением,?|покупатель|клиент)[\\s\\p{Zs}])";

    private static final String CYRILLIC_FIRST_NAMES = "Иван(?:а|у|ом|е)?|Петр(?:а|у|ом|е)?|Пётр|"
            + "Серге(?:й|я|ю|ем|е)|Александр(?:а|у|ом|е)?|"
            + "Алексе(?:й|я|ю|ем|е)|Дмитри(?:й|я|ю|ем|и|е)|"
            + "Максим(?:а|у|ом|е)?|Андре(?:й|я|ю|ем|е)|Михаил(?:а|у|ом|е)?|"
            + "Роман(?:а|у|ом|е)?|Никит(?:а|ы|е|у|ой)|Кирилл(?:а|у|ом|е)?|"
            + "Иль(?:я|и|е|ю|ёй|ей)|Егор(?:а|у|ом|е)?|Васили(?:й|я|ю|ем|и|е)|"
            + "Владимир(?:а|у|ом|е)?|Денис(?:а|у|ом|е)?|Арт[её]м(?:а|у|ом|е)?|"
            + "Павел|Павл(?:а|у|ом|е)|Никола(?:й|я|ю|ем|е)|Евгени(?:й|я|ю|ем|и|е)|"
            + "Владислав(?:а|у|ом|е)?|Борис(?:а|у|ом|е)?|Антон(?:а|у|ом|е)?|"
            + "Виктор(?:а|у|ом|е)?|Игор(?:ь|я|ю|ем|е)|Олег(?:а|у|ом|е)?|"
            + "Юри(?:й|я|ю|ем|и|е)|Даниил(?:а|у|ом|е)?|"
            + "Анн(?:а|ы|е|у|ой)|Мари(?:я|и|ю|ей)|Елен(?:а|ы|е|у|ой)|"
            + "Ольг(?:а|и|е|у|ой)|Наталь(?:я|и|е|ю|ей)|Татьян(?:а|ы|е|у|ой)|"
            + "Екатерин(?:а|ы|е|у|ой)|Светлан(?:а|ы|е|у|ой)|Ирин(?:а|ы|е|у|ой)|"
            + "Юли(?:я|и|ю|ей)|Дарь(?:я|и|е|ю|ей)|Анастаси(?:я|и|ю|ей)|"
            + "Виктори(?:я|и|ю|ей)|Полин(?:а|ы|е|у|ой)|Ксени(?:я|и|ю|ей)|"
            + "Оксан(?:а|ы|е|у|ой)|Марин(?:а|ы|е|у|ой)|Алин(?:а|ы|е|у|ой)|"
            + "Кристин(?:а|ы|е|у|ой)|Соф(?:ия|ьи|ье|ью|ьей|ья)|Надежд(?:а|ы|е|у|ой)";

    private static final String LATIN_FIRST_NAMES =
            "John|Jane|Alex|Alexander|Michael|Mike|David|Daniel|James|Robert|William|Richard|"
                    + "Thomas|Charles|Christopher|Matthew|Andrew|Joshua|Ryan|Kevin|Brian|Mark|Paul|Steven|"
                    + "George|Edward|Peter|Sergey|Ivan|Dmitry|Maxim|Andrey|Mikhail|Nikita|Anna|Maria|"
                    + "Elena|Olga|Natalia|Tatiana|Irina|Julia|Kate|Sarah|Emily|Emma|Jessica|Ashley|"
                    + "Sophia|Olivia";

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[\\p{L}\\p{Nd}._%+-]+@[\\p{L}\\p{Nd}.-]+\\.[\\p{L}]{2,}", Pattern.UNICODE_CASE);

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?<![\\p{L}\\d])(?:\\+7|8|7)[\\s\\p{Zs}\\-().]*(?:\\d[\\s\\p{Zs}\\-().]*){9}\\d(?!\\d)");

    private static final Pattern FULL_PATRONYMIC_FIO_PATTERN = Pattern.compile("(?<![\\p{L}])"
            + "(?:" + NON_SURNAME_GUARD + "(?:[А-ЯЁ][а-яё]{1,25}|[А-ЯЁ]{2,26})[\\s\\p{Zs}]+)?"
            + "(?:[А-ЯЁ][а-яё]{2,20}|[А-ЯЁ]{3,21})(?iu:ович|евич|ич|овна|евна|ична|инична)(?iu:а|у|ом|е|ы|ой|ей)?"
            + "(?:[\\s\\p{Zs}]+" + NON_SURNAME_GUARD + "(?:[А-ЯЁ][а-яё]{1,25}|[А-ЯЁ]{2,26}))?(?![\\p{L}])");

    private static final Pattern NAME_BEFORE_CONTACT_PATTERN = Pattern.compile("(?<![\\p{L}])"
            + "(?:[А-ЯЁ][а-яё]{1,25}(?:[\\s\\p{Zs}]+[А-ЯЁ][а-яё]{1,25}){0,2}"
            + "|[А-ЯЁ]{2,26}(?:[\\s\\p{Zs}]+[А-ЯЁ]{2,26}){0,2}"
            + "|[A-Z][a-z]{1,25}(?:[ \\t]+[A-Z][a-z]{1,25}){0,2}"
            + "|[A-Z]{2,26}(?:[ \\t]+[A-Z]{2,26}){0,2})"
            + "(?=[\\s\\p{Zs}]*,?[\\s\\p{Zs}]*(?:\\[PHONE]|\\[EMAIL]))");

    private static final Pattern LATIN_NAME_BEFORE_COLON_PATTERN = Pattern.compile(
            "(?<![\\p{L}])" + "(?:[A-Z][a-z]{1,25}[ \\t]+[A-Z][a-z]{1,25}|[A-Z]{2,26}[ \\t]+[A-Z]{2,26})(?=[ \\t]*:)");

    private static final Pattern SURNAME_INITIALS_PATTERN =
            Pattern.compile("(?<![\\p{L}])[А-ЯЁ][а-яё]{2,20}[ \\t]+[А-ЯЁ]\\.(?:[ \\t]*[А-ЯЁ]\\.)?");

    private static final Pattern GREETING_OR_SIGNATURE_NAME_PATTERN = Pattern.compile(CUE_LOOKBEHIND
            + NON_SURNAME_GUARD
            + "(?:[А-ЯЁ][а-яё]{1,25}(?:[ \\t]+" + NON_SURNAME_GUARD + "[А-ЯЁ][а-яё]{1,25}){0,2}"
            + "|[А-ЯЁ]{2,26}(?:[ \\t]+" + NON_SURNAME_GUARD + "[А-ЯЁ]{2,26}){0,2}"
            + "|[A-Z][a-z]{1,25}(?:[ \\t]+[A-Z][a-z]{1,25}){0,2}"
            + "|[A-Z]{2,26}(?:[ \\t]+[A-Z]{2,26}){0,2})");

    private static final Pattern LOWERCASE_CUE_NAME_PATTERN = Pattern.compile(
            CUE_LOOKBEHIND
                    + "(?:(?:" + CYRILLIC_FIRST_NAMES.toLowerCase(RU_LOCALE) + ")"
                    + "(?:[\\s\\p{Zs}]+" + NON_SURNAME_GUARD
                    + "[а-яё]{2,20}(?:ович|евич|ич|овна|евна|ична|инична)(?:а|у|ом|е|ы|ой|ей)?)?"
                    + "(?:[\\s\\p{Zs}]+" + NON_SURNAME_GUARD
                    + "[а-яё]{2,20}(?:ов|ев|ёв|ин|ын|ский|цкий|ова|ева|ёва|ина|ына|ская|цкая|енко|ук|юк|ко|их|ых)"
                    + "(?:а|у|ом|е|ой|ей)?)?"
                    + "|(?:" + LATIN_FIRST_NAMES.toLowerCase(Locale.ROOT) + ")[ \\t]+[a-z]{2,25})"
                    + "(?![\\p{L}])",
            Pattern.UNICODE_CASE);

    private static final Pattern LATIN_COMMON_FULL_NAME_PATTERN = Pattern.compile("(?<![\\p{L}])(?:"
            + "(?:" + LATIN_FIRST_NAMES + ")[ \\t]+[A-Z][a-z]{1,25}"
            + "|(?:" + LATIN_FIRST_NAMES.toUpperCase(Locale.ROOT) + ")[ \\t]+[A-Z]{2,26}"
            + ")(?![\\p{L}])");

    private static final Pattern COMMON_FIRST_NAME_PATTERN = Pattern.compile(
            "(?<![\\p{L}])(?:" + CYRILLIC_FIRST_NAMES + ")(?:[\\s\\p{Zs}]+" + NON_SURNAME_GUARD
                    + "[А-ЯЁ][а-яё]{1,25}){0,2}(?![\\p{L}])",
            Pattern.UNICODE_CASE);

    private static final Pattern ALL_CAPS_FIRST_NAME_PATTERN = Pattern.compile(
            "(?<![\\p{L}])(?:" + CYRILLIC_FIRST_NAMES.toUpperCase(RU_LOCALE) + ")(?:[\\s\\p{Zs}]+" + NON_SURNAME_GUARD
                    + "[А-ЯЁ]{2,26}){0,2}(?![\\p{L}])",
            Pattern.UNICODE_CASE);

    public String mask(String rawText) {
        if (rawText == null) {
            return "";
        }
        String result = EMAIL_PATTERN.matcher(rawText).replaceAll(EMAIL_TOKEN);
        result = PHONE_PATTERN.matcher(result).replaceAll(PHONE_TOKEN);
        result = FULL_PATRONYMIC_FIO_PATTERN.matcher(result).replaceAll(NAME_TOKEN);
        result = NAME_BEFORE_CONTACT_PATTERN.matcher(result).replaceAll(NAME_TOKEN);
        result = LATIN_NAME_BEFORE_COLON_PATTERN.matcher(result).replaceAll(NAME_TOKEN);
        result = SURNAME_INITIALS_PATTERN.matcher(result).replaceAll(NAME_TOKEN);
        result = GREETING_OR_SIGNATURE_NAME_PATTERN.matcher(result).replaceAll(NAME_TOKEN);
        result = LOWERCASE_CUE_NAME_PATTERN.matcher(result).replaceAll(NAME_TOKEN);
        result = LATIN_COMMON_FULL_NAME_PATTERN.matcher(result).replaceAll(NAME_TOKEN);
        result = COMMON_FIRST_NAME_PATTERN.matcher(result).replaceAll(NAME_TOKEN);
        return ALL_CAPS_FIRST_NAME_PATTERN.matcher(result).replaceAll(NAME_TOKEN);
    }
}
