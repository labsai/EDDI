/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.semantic;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SemanticFallback")
class SemanticFallbackTest {

    private static final List<SemanticFallback.Example> EXAMPLES = List.of(
            new SemanticFallback.Example("I want to cancel my order", "cancel_order", null),
            new SemanticFallback.Example("give me my money back as a refund", "refund", null),
            new SemanticFallback.Example("wie wird das wetter", "weather", "de"));

    private final KeywordEmbeddingModel model = new KeywordEmbeddingModel("cancel", "order", "refund", "money", "wetter");

    @Test
    @DisplayName("returns the most similar example at or above the minimum score")
    void bestExample() {
        var fallback = new SemanticFallback(() -> model, EXAMPLES, 0.8);

        var match = fallback.classify("please cancel the order", "en");

        assertTrue(match.isPresent());
        assertEquals("cancel_order", match.get().example().expressions());
        assertTrue(match.get().score() >= 0.8);
    }

    @Test
    @DisplayName("returns nothing when no example is similar enough")
    void belowMinimum() {
        var fallback = new SemanticFallback(() -> model, EXAMPLES, 0.8);

        assertTrue(fallback.classify("what a lovely day", "en").isEmpty());
    }

    @Test
    @DisplayName("skips examples of another language")
    void languageFilter() {
        var fallback = new SemanticFallback(() -> model, EXAMPLES, 0.5);

        assertTrue(fallback.classify("wetter", "en").isEmpty());
        assertEquals("weather", fallback.classify("wetter", "de").orElseThrow().example().expressions());
    }

    @Test
    @DisplayName("a turn without a language matches only examples without one")
    void noLanguageIsNoWildcard() {
        var fallback = new SemanticFallback(() -> model, EXAMPLES, 0.5);

        assertTrue(fallback.classify("wetter", null).isEmpty(), "a German example matched a turn of no stated language");
        assertEquals("cancel_order", fallback.classify("cancel order", null).orElseThrow().example().expressions());
    }

    @Test
    @DisplayName("embeds the examples once, in batches, and reuses them")
    void embedsExamplesOnce() {
        List<SemanticFallback.Example> many = new ArrayList<>();
        for (int i = 0; i < SemanticFallback.EMBEDDING_BATCH_SIZE + 1; i++) {
            many.add(new SemanticFallback.Example("cancel order " + i, "cancel_order", null));
        }
        var fallback = new SemanticFallback(() -> model, many, 0.8);

        fallback.classify("cancel order", "en");
        fallback.classify("cancel order", "en");

        // two batches for the examples, one request per input
        assertEquals(4, model.requests.get());
        assertEquals(many.size() + 2, model.embeddedTexts.get());
    }

    /**
     * The factory builds a new client when a variable or secret it depends on
     * changes. The vectors of the old one belong to another embedding space (here
     * even of another length), so they are embedded again with the new one.
     */
    @Test
    @DisplayName("re-embeds the examples when the model changes")
    void reEmbedsWhenTheModelChanges() {
        var narrow = new KeywordEmbeddingModel("cancel", "order");
        var current = new AtomicReference<EmbeddingModel>(narrow);
        var fallback = new SemanticFallback(current::get, EXAMPLES, 0.8);
        assertEquals("cancel_order", fallback.classify("cancel order", "en").orElseThrow().example().expressions());

        current.set(model);

        assertEquals("refund", fallback.classify("refund my money", "en").orElseThrow().example().expressions());
        assertEquals(EXAMPLES.size() + 1, model.embeddedTexts.get(), "the examples were not embedded with the new model");
        fallback.classify("refund my money", "en");
        assertEquals(EXAMPLES.size() + 2, model.embeddedTexts.get(), "an unchanged model re-embedded the examples");
    }

    @Test
    @DisplayName("a provider failure is no match, and the examples are retried only after the back-off")
    void failureBacksOff() {
        var now = new AtomicLong(0);
        var fallback = new SemanticFallback(() -> model, EXAMPLES, 0.8, now::get);
        model.failure = new IllegalStateException("provider down");

        assertTrue(fallback.classify("cancel order", "en").isEmpty());
        assertTrue(fallback.classify("cancel order", "en").isEmpty());
        assertEquals(1, model.requests.get(), "retried before the back-off ran out");

        model.failure = null;
        now.addAndGet(SemanticFallback.RETRY_AFTER_FAILURE.toNanos() + TimeUnit.SECONDS.toNanos(1));

        assertTrue(fallback.classify("cancel order", "en").isPresent());
    }

    @Test
    @DisplayName("a failure while embedding the input is no match")
    void inputFailure() {
        var fallback = new SemanticFallback(() -> model, EXAMPLES, 0.8);
        fallback.classify("cancel order", "en");
        model.failure = new IllegalStateException("timeout");

        assertTrue(fallback.classify("cancel order", "en").isEmpty());
    }

    @Test
    @DisplayName("an over-long input is cut before it is sent")
    void longInputIsCut() {
        var seen = new ArrayList<Integer>();
        var recording = new KeywordEmbeddingModel("cancel") {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                segments.forEach(segment -> seen.add(segment.text().length()));
                return super.embedAll(segments);
            }
        };
        var fallback = new SemanticFallback(() -> recording, EXAMPLES, 0.8);

        fallback.classify("cancel ".repeat(1000), "en");

        assertEquals(SemanticFallback.MAX_INPUT_CHARS, seen.getLast());
    }

    @Test
    @DisplayName("blank input or an interrupted thread never reaches the provider")
    void shortCircuits() {
        var fallback = new SemanticFallback(() -> model, EXAMPLES, 0.8);

        assertTrue(fallback.classify("", "en").isEmpty());
        Thread.currentThread().interrupt();
        try {
            assertTrue(fallback.classify("cancel order", "en").isEmpty());
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, model.requests.get());
    }
}
