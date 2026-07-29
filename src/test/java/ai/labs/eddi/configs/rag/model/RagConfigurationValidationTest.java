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
    @DisplayName("the default configuration is implementable as written")
    void defaultConfigurationIsSupported() {
        var config = new RagConfiguration();

        assertEquals("recursive", config.getChunkStrategy());
        assertNull(config.findUnsupportedSettings());
        assertNull(config.normalizeLegacyChunkStrategy());
        config.validate();
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
