package ai.labs.eddi.modules.llm.tools.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TextSummarizerTool.
 */
class TextSummarizerToolTest {

    private TextSummarizerTool textSummarizerTool;

    @BeforeEach
    void setUp() {
        textSummarizerTool = new TextSummarizerTool();
    }

    @Test
    void testSummarizeText_ShortText() {
        String text = "This is a short text that doesn't need much summarization.";
        String result = textSummarizerTool.summarizeText(text, 50);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
    }

    @Test
    void testSummarizeText_LongText() {
        String text = "This is a longer text. ".repeat(50) + 
                     "It contains multiple sentences and paragraphs. " +
                     "The summarizer should extract the most important information.";
        String result = textSummarizerTool.summarizeText(text, 100);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
    }

    @Test
    void testSummarizeText_EmptyText() {
        String result = textSummarizerTool.summarizeText("", 50);
        assertNotNull(result);
        // Should handle empty text gracefully
    }

    @Test
    void testSummarizeText_NullMaxWords() {
        String text = "This is some text to summarize.";
        String result = textSummarizerTool.summarizeText(text, null);
        assertNotNull(result);
        // Should use default max words
    }

    @Test
    void testSummarizeText_ZeroMaxWords() {
        String text = "This is some text to summarize.";
        String result = textSummarizerTool.summarizeText(text, 0);
        assertNotNull(result);
        // Should handle gracefully
    }

    @Test
    void testExtractKeywords_SimpleText() {
        String text = "Java programming language is object-oriented. " +
                     "Java is platform independent. Java runs on JVM.";
        String result = textSummarizerTool.extractKeywords(text, 5);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        // Should contain relevant keywords
    }

    @Test
    void testExtractKeywords_EmptyText() {
        String result = textSummarizerTool.extractKeywords("", 5);
        assertNotNull(result);
    }

    @Test
    void testExtractKeywords_NullMaxKeywords() {
        String text = "Some text with keywords to extract.";
        String result = textSummarizerTool.extractKeywords(text, null);
        assertNotNull(result);
        // Should use default
    }

    @Test
    void testExtractKeywords_LongText() {
        String text = "Natural language processing (NLP) is a branch of artificial intelligence. " +
                     "NLP helps computers understand human language. " +
                     "Machine learning algorithms are used in NLP. " +
                     "Deep learning has improved NLP capabilities.";
        String result = textSummarizerTool.extractKeywords(text, 10);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
    }
}

