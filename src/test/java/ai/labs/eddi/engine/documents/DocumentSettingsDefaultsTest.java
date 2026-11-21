/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operator-property holder. Every package that adds a documents property
 * appends one block here asserting its default equals the design's table (§14).
 */
@DisplayName("DocumentSettings — operator defaults")
class DocumentSettingsDefaultsTest {

    @Test
    @DisplayName("no operator setting: no default, no bound")
    void none() {
        var settings = DocumentSettings.none();
        assertEquals(Optional.empty(), settings.defaultFor("split.maxUnitTokens"));
        assertTrue(settings.bounds().boundFor("split.maxUnitTokens").isEmpty());
    }

    @Test
    @DisplayName("defaults are keyed by designer path; blank values count as unset")
    void lookup() {
        var settings = DocumentSettings.of(Map.of("split.maxUnitTokens", " 8000 ", "large.onReady", "  "), DocumentBounds.none());
        assertEquals(Optional.of("8000"), settings.defaultFor("split.maxUnitTokens"));
        assertEquals(Optional.empty(), settings.defaultFor("large.onReady"));
        assertEquals("eddi.documents.defaults.", DocumentSettings.DEFAULTS_PREFIX);
    }

    // --- WP-0.1: later packages append one block per property below ---

    // --- WP-1.2 ---

    @Test
    @DisplayName("WP-1.2: the sharing counts are bounded by default, without any operator property")
    void sharingBoundsHaveBuiltInDefaults() {
        assertEquals(Map.of("sharing.maxFilesPerShare", "eddi.documents.sharing.max-files-per-share", "sharing.maxSharesPerTurn",
                "eddi.documents.sharing.max-shares-per-turn", "sharing.maxDepth", "eddi.documents.sharing.max-depth"),
                DocumentBounds.BOUND_PROPERTIES);
        var bounds = DocumentBounds.current();
        assertEquals(0, new BigDecimal("5").compareTo(bounds.boundFor("sharing.maxFilesPerShare").orElseThrow()));
        assertEquals(0, new BigDecimal("10").compareTo(bounds.boundFor("sharing.maxSharesPerTurn").orElseThrow()));
        assertEquals(0, new BigDecimal("3").compareTo(bounds.boundFor("sharing.maxDepth").orElseThrow()));
        assertTrue(bounds.boundFor("split.maxUnitTokens").isEmpty());
    }

    @Test
    @DisplayName("WP-1.2: every built-in bound belongs to a bounded path")
    void everyBuiltInBoundHasAProperty() {
        assertTrue(DocumentBounds.BOUND_PROPERTIES.keySet().containsAll(DocumentBounds.BOUND_DEFAULTS.keySet()),
                DocumentBounds.BOUND_DEFAULTS.toString());
    }

    @Test
    @DisplayName("WP-1.2: every bound property is actually read")
    void everyBoundPropertyIsRead() {
        DocumentBounds.BOUND_PROPERTIES.values().forEach(property -> System.setProperty(property, "0"));
        try {
            var bounds = DocumentBounds.current();
            DocumentBounds.BOUND_PROPERTIES.keySet().forEach(path -> assertEquals(0,
                    BigDecimal.ZERO.compareTo(bounds.boundFor(path).orElseThrow()), path + " ignores its operator property"));
        } finally {
            DocumentBounds.BOUND_PROPERTIES.values().forEach(System::clearProperty);
        }
    }

    @Test
    @DisplayName("WP-1.2: an operator property replaces the built-in bound; a bad one falls back to it")
    void operatorPropertyReplacesTheBuiltInBound() {
        System.setProperty("eddi.documents.sharing.max-depth", "1");
        System.setProperty("eddi.documents.sharing.max-files-per-share", "lots");
        try {
            var bounds = DocumentBounds.current();
            assertEquals(0, BigDecimal.ONE.compareTo(bounds.boundFor("sharing.maxDepth").orElseThrow()));
            assertEquals(0, new BigDecimal("5").compareTo(bounds.boundFor("sharing.maxFilesPerShare").orElseThrow()));
        } finally {
            System.clearProperty("eddi.documents.sharing.max-depth");
            System.clearProperty("eddi.documents.sharing.max-files-per-share");
        }
    }
}
