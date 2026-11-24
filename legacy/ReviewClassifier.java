import java.io.*;
import java.util.*;

public class ReviewClassifier {
    private static final String MODEL_PATH = "C:\\models\\sentiment_v1.bin";
    private static final String LOG_PATH = "C:\\logs\\reviews.log";
    
    private Map<String, Double> weights;
    
    public ReviewClassifier() throws Exception {
        weights = new HashMap<>();
        // Загрузка модели
        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream(MODEL_PATH))) {
            weights = (Map<String, Double>) ois.readObject();
        }
    }
    
    public String classify(String reviewText) {
        System.out.println("[DEBUG] Review: " + reviewText);
        
        double score = 0;
        for (String word : reviewText.split(" ")) {
            if (weights.containsKey(word)) {
                score += weights.get(word);
            }
        }
        
        String result = score > 0 ? "positive" : "negative";
        
        // Логируем для аудита
        FileWriter fw = new FileWriter(LOG_PATH, true);
        fw.write(reviewText + " | " + result + "\n");
        fw.close();
        
        return result;
    }
    
    public static void main(String[] args) throws Exception {
        ReviewClassifier classifier = new ReviewClassifier();
        System.out.println(classifier.classify(
            "Товар ужасный, сломался через день! Иван, +7-999-123-45-67"));
    }
}
