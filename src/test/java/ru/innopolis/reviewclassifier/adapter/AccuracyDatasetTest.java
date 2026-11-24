package ru.innopolis.reviewclassifier.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.innopolis.reviewclassifier.adapters.outbound.JsonMapperFactory;
import ru.innopolis.reviewclassifier.adapters.outbound.JsonModelWeightsLoader;
import ru.innopolis.reviewclassifier.domain.model.Sentiment;
import ru.innopolis.reviewclassifier.domain.service.PiiMasker;
import ru.innopolis.reviewclassifier.domain.service.SentimentScoringService;
import ru.innopolis.reviewclassifier.domain.service.TextPreprocessor;

/**
 * Evaluates baseline unigram classification accuracy against the production model dictionary
 * over an internal curated 40-review Russian e-commerce regression dataset (>= 89% regression gate;
 * formal NFR-03 / AC-21 acceptance requires an external DS holdout dataset).
 */
class AccuracyDatasetTest {

    private record Sample(String text, Sentiment expected) {}

    private static final List<Sample> CONTROL_DATASET = List.of(
            new Sample("Отличный ноутбук, очень доволен покупкой", Sentiment.POSITIVE),
            new Sample("Супер качество, удобно и быстро", Sentiment.POSITIVE),
            new Sample("Хороший товар, рекомендую всем", Sentiment.POSITIVE),
            new Sample("Прекрасный экран, нравится дизайн", Sentiment.POSITIVE),
            new Sample("Качественный утюг, работает хорошо", Sentiment.POSITIVE),
            new Sample("Спасибо магазину, хороший сервис", Sentiment.POSITIVE),
            new Sample("Удобный чехол, нормальный материал", Sentiment.POSITIVE),
            new Sample("Супер телефон, доволен на сто процентов", Sentiment.POSITIVE),
            new Sample("Очень качественный пылесос, рекомендую", Sentiment.POSITIVE),
            new Sample("Прекрасный звук, всё отлично", Sentiment.POSITIVE),
            new Sample("Хорошо упаковано, быстро приехало, спасибо", Sentiment.POSITIVE),
            new Sample("Отличный подарок, всем нравится", Sentiment.POSITIVE),
            new Sample("Хороший и удобный стул", Sentiment.POSITIVE),
            new Sample("Прекрасный кофеварка, супер", Sentiment.POSITIVE),
            new Sample("Доволен покупкой, качественный корпус", Sentiment.POSITIVE),
            new Sample("Отличный выбор, рекомендую однозначно", Sentiment.POSITIVE),
            new Sample("Супер наушники, звучат хорошо", Sentiment.POSITIVE),
            new Sample("Удобный интерфейс, нравится скорость", Sentiment.POSITIVE),
            new Sample("Спасибо за отличный сервис", Sentiment.POSITIVE),
            new Sample("Нормальный рюкзак, удобный и качественный", Sentiment.POSITIVE),
            new Sample("Ужасный товар, сломался через день", Sentiment.NEGATIVE),
            new Sample("Явный брак, поломанный корпус", Sentiment.NEGATIVE),
            new Sample("Пришел бракованный чайник, верните деньги", Sentiment.NEGATIVE),
            new Sample("Курьер опоздали, задержка два дня", Sentiment.NEGATIVE),
            new Sample("Товар неисправен, дефект на экране", Sentiment.NEGATIVE),
            new Sample("Плохой сервис, разочарован полностью", Sentiment.NEGATIVE),
            new Sample("Отвратительный запах пластика, ужасно", Sentiment.NEGATIVE),
            new Sample("Заказ не доставили, жду возврат", Sentiment.NEGATIVE),
            new Sample("Кнопка сломалась сразу, бракованная партия", Sentiment.NEGATIVE),
            new Sample("Поломка на второй день, дефектный блок", Sentiment.NEGATIVE),
            new Sample("Посылка пришла вскрытой, не привезли часть", Sentiment.NEGATIVE),
            new Sample("Разочарован качеством, плохо работает", Sentiment.NEGATIVE),
            new Sample("Верните средства, устройство сломалось", Sentiment.NEGATIVE),
            new Sample("Ужасный пластик, неисправный разъём", Sentiment.NEGATIVE),
            new Sample("Задержали доставку на неделю, плохо", Sentiment.NEGATIVE),
            new Sample("Сломали крепление при перевозке, брак", Sentiment.NEGATIVE),
            new Sample("Отвратительный пылесос, поломка мотора", Sentiment.NEGATIVE),
            new Sample("Дефект краски и бракованное стекло", Sentiment.NEGATIVE),
            new Sample("Не рекомендую этот ужасный товар", Sentiment.NEGATIVE),
            new Sample("Брака нет, дефектов нет", Sentiment.POSITIVE));

    @Test
    void achievesAtLeast89PercentAccuracyOnControlDataset() {
        JsonModelWeightsLoader loader = new JsonModelWeightsLoader(JsonMapperFactory.create());
        loader.load(Path.of("data/sentiment_v1.json"), Path.of("data/sentiment_v1.json.sha256"));
        Map<String, Double> weights = loader.getWeights();

        PiiMasker masker = new PiiMasker();
        TextPreprocessor preprocessor = new TextPreprocessor();
        SentimentScoringService scoringService = new SentimentScoringService();

        int correct = 0;
        for (Sample sample : CONTROL_DATASET) {
            Sentiment predicted = scoringService.classify(preprocessor.tokenize(masker.mask(sample.text())), weights);
            if (predicted == sample.expected()) {
                correct++;
            }
        }

        double accuracy = (double) correct / CONTROL_DATASET.size();
        assertThat(accuracy).isGreaterThanOrEqualTo(0.89);
    }
}
