package ai.labs.eddi.modules.llm.tools.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Text summarization and processing tool.
 * Provides extractive summarization and text analysis.
 */
@ApplicationScoped
public class TextSummarizerTool {
    private static final Logger LOGGER = Logger.getLogger(TextSummarizerTool.class);

    @Tool("Summarizes long text by extracting the most important sentences. Good for quick overviews of articles or documents.")
    public String summarizeText(
            @P("text") String text,
            @P("numSentences") Integer numSentences) {

        try {
            if (numSentences == null || numSentences < 1) {
                numSentences = 3;
            }
            if (numSentences > 10) {
                numSentences = 10;
            }

            LOGGER.debug("Summarizing text to " + numSentences + " sentences");

            // Split into sentences
            String[] sentences = text.split("[.!?]+\\s+");

            if (sentences.length <= numSentences) {
                return "Summary:\n" + text;
            }

            // Score sentences based on word frequency
            Map<String, Integer> wordFreq = calculateWordFrequency(text);
            Map<String, Double> sentenceScores = scoreSentences(sentences, wordFreq);

            // Get top sentences
            List<Map.Entry<String, Double>> topSentences = sentenceScores.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(numSentences)
                    .toList();

            // Maintain original order
            List<String> selectedSentences = new ArrayList<>();
            for (String sentence : sentences) {
                if (topSentences.stream().anyMatch(e -> e.getKey().equals(sentence))) {
                    selectedSentences.add(sentence.trim());
                }
            }

            String summary = String.join(". ", selectedSentences);
            if (!summary.endsWith(".")) {
                summary += ".";
            }

            LOGGER.info("Summarized " + sentences.length + " sentences to " + numSentences);
            return "Summary:\n" + summary;

        } catch (Exception e) {
            LOGGER.error("Text summarization error: " + e.getMessage());
            return "Error: Could not summarize text - " + e.getMessage();
        }
    }

    @Tool("Counts words, sentences, and characters in text")
    public String analyzeText(
            @P("text") String text) {

        try {
            int charCount = text.length();
            int wordCount = text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
            int sentenceCount = text.split("[.!?]+").length;
            int paragraphCount = text.split("\n\n+").length;

            // Calculate average word length
            String[] words = text.split("\\s+");
            double avgWordLength = words.length > 0 ?
                Arrays.stream(words).mapToInt(String::length).average().orElse(0) : 0;

            // Estimate reading time (average 200 words per minute)
            int readingTimeMinutes = (int) Math.ceil(wordCount / 200.0);

            StringBuilder result = new StringBuilder();
            result.append("Text Analysis:\n\n");
            result.append("Characters: ").append(charCount).append("\n");
            result.append("Words: ").append(wordCount).append("\n");
            result.append("Sentences: ").append(sentenceCount).append("\n");
            result.append("Paragraphs: ").append(paragraphCount).append("\n");
            result.append("Average word length: ").append(String.format("%.1f", avgWordLength)).append(" characters\n");
            result.append("Estimated reading time: ").append(readingTimeMinutes).append(" minute(s)\n");

            LOGGER.debug("Text analyzed: " + wordCount + " words, " + sentenceCount + " sentences");
            return result.toString();

        } catch (Exception e) {
            LOGGER.error("Text analysis error: " + e.getMessage());
            return "Error: Could not analyze text - " + e.getMessage();
        }
    }

    @Tool("Extracts keywords from text based on frequency and importance")
    public String extractKeywords(
            @P("text") String text,
            @P("numKeywords") Integer numKeywords) {

        try {
            if (numKeywords == null || numKeywords < 1) {
                numKeywords = 10;
            }
            if (numKeywords > 20) {
                numKeywords = 20;
            }

            LOGGER.debug("Extracting " + numKeywords + " keywords");

            // Calculate word frequency
            Map<String, Integer> wordFreq = calculateWordFrequency(text);

            // Filter out stop words and short words
            Set<String> stopWords = getStopWords();
            Map<String, Integer> filteredFreq = wordFreq.entrySet().stream()
                    .filter(e -> !stopWords.contains(e.getKey()))
                    .filter(e -> e.getKey().length() > 3)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            // Get top keywords
            List<Map.Entry<String, Integer>> topKeywords = filteredFreq.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(numKeywords)
                    .toList();

            StringBuilder result = new StringBuilder();
            result.append("Top Keywords:\n\n");

            int rank = 1;
            for (Map.Entry<String, Integer> entry : topKeywords) {
                result.append(rank++).append(". ").append(entry.getKey())
                      .append(" (").append(entry.getValue()).append(" occurrences)\n");
            }

            LOGGER.info("Extracted " + topKeywords.size() + " keywords");
            return result.toString();

        } catch (Exception e) {
            LOGGER.error("Keyword extraction error: " + e.getMessage());
            return "Error: Could not extract keywords - " + e.getMessage();
        }
    }

    @Tool("Removes extra whitespace and normalizes text formatting")
    public String normalizeText(
            @P("text") String text) {

        try {
            // Remove extra whitespace
            String normalized = text.replaceAll("\\s+", " ");

            // Remove leading/trailing whitespace
            normalized = normalized.trim();

            // Normalize line breaks
            normalized = normalized.replaceAll("\\. ", ".\n");

            LOGGER.debug("Text normalized");
            return normalized;

        } catch (Exception e) {
            LOGGER.error("Text normalization error: " + e.getMessage());
            return "Error: Could not normalize text - " + e.getMessage();
        }
    }

    private Map<String, Integer> calculateWordFrequency(String text) {
        Map<String, Integer> freq = new HashMap<>();

        // Clean and split text
        String cleaned = text.toLowerCase()
                            .replaceAll("[^a-z0-9\\s]", " ")
                            .replaceAll("\\s+", " ");

        String[] words = cleaned.split(" ");

        for (String word : words) {
            word = word.trim();
            if (!word.isEmpty()) {
                freq.put(word, freq.getOrDefault(word, 0) + 1);
            }
        }

        return freq;
    }

    private Map<String, Double> scoreSentences(String[] sentences, Map<String, Integer> wordFreq) {
        Map<String, Double> scores = new HashMap<>();

        for (String sentence : sentences) {
            double score = 0;
            String[] words = sentence.toLowerCase().split("\\s+");

            for (String word : words) {
                String cleaned = word.replaceAll("[^a-z0-9]", "");
                score += wordFreq.getOrDefault(cleaned, 0);
            }

            // Normalize by sentence length
            if (words.length > 0) {
                score /= words.length;
            }

            scores.put(sentence, score);
        }

        return scores;
    }

    private Set<String> getStopWords() {
        return new HashSet<>(Arrays.asList(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
            "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
            "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
            "or", "an", "will", "my", "one", "all", "would", "there", "their",
            "what", "so", "up", "out", "if", "about", "who", "get", "which", "go",
            "me", "when", "make", "can", "like", "time", "no", "just", "him", "know",
            "take", "people", "into", "year", "your", "good", "some", "could", "them",
            "see", "other", "than", "then", "now", "look", "only", "come", "its", "over"
        ));
    }
}

