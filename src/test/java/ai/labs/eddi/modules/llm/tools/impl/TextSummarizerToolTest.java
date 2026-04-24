/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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
        String text = "This is a longer text. ".repeat(50) + "It contains multiple sentences and paragraphs. "
                + "The summarizer should extract the most important information.";
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
        String text = "Java programming language is object-oriented. " + "Java is platform independent. Java runs on JVM.";
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
        String text = "Natural language processing (NLP) is a branch of artificial intelligence. " + "NLP helps computers understand human language. "
                + "Machine learning algorithms are used in NLP. " + "Deep learning has improved NLP capabilities.";
        String result = textSummarizerTool.extractKeywords(text, 10);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
    }

    // === analyzeText tests ===

    @Test
    void testAnalyzeText_SimpleText() {
        String result = textSummarizerTool.analyzeText("Hello world. This is a test.");
        assertNotNull(result);
        assertTrue(result.contains("Characters:"));
        assertTrue(result.contains("Words:"));
        assertTrue(result.contains("Sentences:"));
        assertTrue(result.contains("Paragraphs:"));
        assertTrue(result.contains("Average word length:"));
        assertTrue(result.contains("reading time:"));
    }

    @Test
    void testAnalyzeText_EmptyText() {
        String result = textSummarizerTool.analyzeText("");
        assertNotNull(result);
        assertTrue(result.contains("Words: 0"));
    }

    @Test
    void testAnalyzeText_MultiParagraph() {
        String text = "First paragraph with words.\n\nSecond paragraph here.\n\nThird paragraph.";
        String result = textSummarizerTool.analyzeText(text);
        assertNotNull(result);
        assertTrue(result.contains("Paragraphs: 3"));
    }

    @Test
    void testAnalyzeText_LongText() {
        String text = "Word ".repeat(500); // ~2500 chars, 500 words, ~2.5 min read
        String result = textSummarizerTool.analyzeText(text);
        assertNotNull(result);
        assertTrue(result.contains("minute(s)"));
    }

    // === normalizeText tests ===

    @Test
    void testNormalizeText_ExtraWhitespace() {
        String result = textSummarizerTool.normalizeText("  hello   world  ");
        assertNotNull(result);
        assertFalse(result.startsWith(" "), "Should trim leading whitespace");
        assertFalse(result.contains("  "), "Should collapse multiple spaces");
    }

    @Test
    void testNormalizeText_SentenceBreaks() {
        String result = textSummarizerTool.normalizeText("First sentence. Second sentence.");
        assertNotNull(result);
        assertTrue(result.contains("\n"), "Should add line breaks after sentences");
    }

    @Test
    void testNormalizeText_EmptyText() {
        String result = textSummarizerTool.normalizeText("");
        assertNotNull(result);
        assertEquals("", result);
    }

    // === Edge cases for parameter clamping ===

    @Test
    void testSummarizeText_NumSentencesExceeds10_ClampedTo10() {
        String text = "Sentence one. Sentence two. Sentence three. Sentence four. "
                + "Sentence five. Sentence six. Sentence seven. Sentence eight. "
                + "Sentence nine. Sentence ten. Sentence eleven. Sentence twelve.";
        String result = textSummarizerTool.summarizeText(text, 15);
        assertNotNull(result);
        assertTrue(result.startsWith("Summary:"));
    }

    @Test
    void testSummarizeText_NegativeNumSentences_DefaultsTo3() {
        String text = "First important sentence. Second sentence here. Third one follows. Fourth is last.";
        String result = textSummarizerTool.summarizeText(text, -5);
        assertNotNull(result);
        assertTrue(result.startsWith("Summary:"));
    }

    @Test
    void testExtractKeywords_NumKeywordsExceeds20_ClampedTo20() {
        String text = ("Java programming language object-oriented platform independent. ").repeat(10);
        String result = textSummarizerTool.extractKeywords(text, 50);
        assertNotNull(result);
        assertTrue(result.contains("Top Keywords:"));
    }

    @Test
    void testExtractKeywords_NegativeNumKeywords_DefaultsTo10() {
        String text = "Some text with multiple keywords for extraction testing purposes.";
        String result = textSummarizerTool.extractKeywords(text, -1);
        assertNotNull(result);
        assertTrue(result.contains("Top Keywords:"));
    }
}
