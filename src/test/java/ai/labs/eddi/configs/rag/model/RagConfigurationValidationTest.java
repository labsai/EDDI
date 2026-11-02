/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding I3 — {@code chunkStrategy} names a splitter the ingestion pipeline
 * may not implement.
 * <p>
 * Three distinct behaviors are pinned here:
 * <ul>
 * <li>a value the engine implements passes untouched,</li>
 * <li>the two values the class javadoc used to advertise ({@code paragraph},
 * {@code sentence}) are rewritten to {@code recursive} — they always produced
 * recursive splitting, so stored knowledge bases must stay writable,</li>
 * <li>anything else is an error, but only where it can be acted on (the
 * create/update boundary), never on the retrieval path.</li>
 * </ul>
 */
class RagConfigurationValidationTest {

    @Test
    @DisplayName("a chunk overlap above half the chunk size, or a chunk size out of range, is refused at save time")
    void chunkSettingsAreBounded() {
        // Overlap close to the chunk size re-stores almost a whole chunk for every
        // few characters of new text, multiplying memory and embedding cost.
        var nearTotalOverlap = new RagConfiguration();
        nearTotalOverlap.setChunkSize(1000);
        nearTotalOverlap.setChunkOverlap(999);
        assertThrows(IllegalArgumentException.class, nearTotalOverlap::validate);

        var halfOverlap = new RagConfiguration();
        halfOverlap.setChunkSize(1000);
        halfOverlap.setChunkOverlap(500);
        halfOverlap.validate();

        var negativeOverlap = new RagConfiguration();
        negativeOverlap.setChunkOverlap(-1);
        assertThrows(IllegalArgumentException.class, negativeOverlap::validate);

        var zeroSize = new RagConfiguration();
        zeroSize.setChunkSize(0);
        assertThrows(IllegalArgumentException.class, zeroSize::validate);

        var hugeSize = new RagConfiguration();
        hugeSize.setChunkSize(RagConfiguration.MAX_CHUNK_SIZE + 1);
        assertThrows(IllegalArgumentException.class, hugeSize::validate);
    }

    @Test
    @DisplayName("a stored configuration that predates the bounds is clamped where ingestion reads it")
    void storedChunkSettingsAreClamped() {
        var stored = new RagConfiguration();
        stored.setChunkSize(1000);
        stored.setChunkOverlap(999);
        assertEquals(1000, stored.chunkSizeOrDefault());
        assertEquals(500, stored.chunkOverlapOrDefault());

        var unset = new RagConfiguration();
        unset.setChunkSize(null);
        unset.setChunkOverlap(null);
        assertEquals(512, unset.chunkSizeOrDefault());
        assertEquals(64, unset.chunkOverlapOrDefault());
    }

    @Test
    @DisplayName("the default configuration is implementable as written")
    void defaultConfigurationIsSupported() {
        var config = new RagConfiguration();

        assertEquals("recursive", config.getChunkStrategy());
        assertNull(config.findUnsupportedSettings());
        assertNull(config.normalizeLegacyChunkStrategy());
        config.validate();
    }

    @Test
    @DisplayName("an unknown embeddingProvider is refused at save time, not at the first ingestion")
    void unknownEmbeddingProviderIsUnsupported() {
        var config = new RagConfiguration();
        config.setName("kb");
        config.setEmbeddingProvider("carrier-pigeon");

        String message = config.findUnsupportedSettings();

        assertTrue(message != null && message.contains("carrier-pigeon") && message.contains("openai"), message);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @DisplayName("every provider the embedding factory builds is accepted")
    void supportedEmbeddingProvidersAreAccepted() {
        for (String provider : RagConfiguration.SUPPORTED_EMBEDDING_PROVIDERS) {
            var config = new RagConfiguration();
            config.setEmbeddingProvider(provider);
            assertNull(config.findUnsupportedSettings(), provider);
        }
    }

    @Test
    @DisplayName("chunkStrategy is matched case-insensitively and trimmed")
    void supportedStrategyIsNormalizedForComparison() {
        var config = new RagConfiguration();
        config.setChunkStrategy("  Recursive  ");

        assertNull(config.findUnsupportedSettings());
        config.validate();
        assertEquals("  Recursive  ", config.getChunkStrategy(), "a supported value must not be rewritten");
    }

    @Test
    @DisplayName("null or blank chunkStrategy is not a complaint")
    void absentStrategyIsSupported() {
        var nullStrategy = new RagConfiguration();
        nullStrategy.setChunkStrategy(null);
        assertNull(nullStrategy.findUnsupportedSettings());
        assertNull(nullStrategy.normalizeLegacyChunkStrategy());

        var blankStrategy = new RagConfiguration();
        blankStrategy.setChunkStrategy("   ");
        assertNull(blankStrategy.findUnsupportedSettings());
        assertNull(blankStrategy.normalizeLegacyChunkStrategy());
        assertEquals("   ", blankStrategy.getChunkStrategy());
    }

    @Test
    @DisplayName("an unimplemented chunkStrategy is reported with an actionable message")
    void unsupportedStrategyIsReported() {
        var config = new RagConfiguration();
        config.setName("product-docs");
        config.setChunkStrategy("semantic");

        String message = config.findUnsupportedSettings();
        assertTrue(message != null && message.contains("semantic"), "message must name the offending value: " + message);
        assertTrue(message.contains("product-docs"), "message must name the knowledge base: " + message);
        assertTrue(message.contains("recursive"), "message must name the supported value: " + message);

        var thrown = assertThrows(IllegalArgumentException.class, config::validate);
        assertEquals(message, thrown.getMessage());
    }

    @Test
    @DisplayName("'paragraph' and 'sentence' are normalized to recursive, not rejected")
    void legacyStrategiesAreNormalized() {
        var paragraph = new RagConfiguration();
        paragraph.setChunkStrategy("paragraph");

        String note = paragraph.normalizeLegacyChunkStrategy();
        assertTrue(note != null && note.contains("paragraph"), "the rewrite must be reported: " + note);
        assertEquals("recursive", paragraph.getChunkStrategy());
        assertNull(paragraph.findUnsupportedSettings(), "after normalization there is nothing left to complain about");
        paragraph.validate();

        var sentence = new RagConfiguration();
        sentence.setChunkStrategy("  Sentence ");
        assertTrue(sentence.normalizeLegacyChunkStrategy() != null);
        assertEquals("recursive", sentence.getChunkStrategy());
    }

    @Test
    @DisplayName("normalization leaves an unknown strategy alone so it still fails validation")
    void unknownStrategyIsNotNormalizedAway() {
        var config = new RagConfiguration();
        config.setChunkStrategy("semantic");

        assertNull(config.normalizeLegacyChunkStrategy());
        assertEquals("semantic", config.getChunkStrategy());
        assertThrows(IllegalArgumentException.class, config::validate);
    }
}
