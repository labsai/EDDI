/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import ai.labs.eddi.engine.attachments.DerivedCaps;
import ai.labs.eddi.engine.documents.spend.DocumentSpendLimits;
import ai.labs.eddi.modules.ingestion.extract.ImageLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

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
        // Entries, not equality: BOUND_PROPERTIES is append-only, and later packages
        // add to it.
        assertTrue(DocumentBounds.BOUND_PROPERTIES.entrySet()
                .containsAll(Map.of("sharing.maxFilesPerShare", "eddi.documents.sharing.max-files-per-share", "sharing.maxSharesPerTurn",
                        "eddi.documents.sharing.max-shares-per-turn", "sharing.maxDepth", "eddi.documents.sharing.max-depth").entrySet()),
                DocumentBounds.BOUND_PROPERTIES.toString());
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

    // --- WP-2a.2 ---

    @Test
    @DisplayName("derived-blob caps: the design's defaults, from the deployment's configuration")
    void derivedCaps() {
        var expected = new DerivedCaps.Limits(200, 52_428_800L, 20, 52_428_800L, 209_715_200L);
        assertEquals(expected, DocumentSettings.none().derivedCaps());
        assertEquals(expected, new DocumentSettings().derivedCaps(), "no property set in the test configuration");
    }

    // --- WP-2b.2a ---

    @Test
    @DisplayName("WP-2b.2a: images.max-pixels is 40 MP unless the operator sets a positive number")
    void imagesMaxPixels() {
        assertEquals(40_000_000L, DocumentSettings.none().imagesMaxPixels());
        assertEquals(ImageLimits.DEFAULT_MAX_PIXELS, DocumentSettings.DEFAULT_IMAGES_MAX_PIXELS);
        var cdi = new DocumentSettings();
        assertEquals(40_000_000L, cdi.imagesMaxPixels());
        try {
            System.setProperty(DocumentSettings.IMAGES_MAX_PIXELS, "1000000");
            assertEquals(1_000_000L, cdi.imagesMaxPixels());
            System.setProperty(DocumentSettings.IMAGES_MAX_PIXELS, "0");
            assertEquals(40_000_000L, cdi.imagesMaxPixels());
            System.setProperty(DocumentSettings.IMAGES_MAX_PIXELS, "lots");
            assertEquals(40_000_000L, cdi.imagesMaxPixels());
        } finally {
            System.clearProperty(DocumentSettings.IMAGES_MAX_PIXELS);
        }
    }

    // --- WP-4b.1 ---

    @Test
    @DisplayName("daily document spend: no ceiling by default, buckets kept 35 days")
    void spendLimits() {
        var expected = new DocumentSpendLimits(OptionalDouble.empty(), OptionalDouble.empty(), 35);
        assertEquals(expected, DocumentSettings.none().spendLimits());
        assertEquals(expected, new DocumentSettings().spendLimits(), "no ceiling set in the test configuration");
        assertEquals("eddi.documents.spend.max-per-user-per-day-usd", DocumentSpendLimits.MAX_PER_USER_PER_DAY);
        assertEquals("eddi.documents.spend.max-per-team-per-day-usd", DocumentSpendLimits.MAX_PER_TEAM_PER_DAY);
        assertEquals("eddi.documents.spend.retention-days", DocumentSpendLimits.RETENTION_DAYS);
    }

    @Test
    @DisplayName("daily document spend: a negative ceiling means none, 0 is a ceiling, retention is at least one day")
    void spendLimitsNormalised() {
        var limits = new DocumentSpendLimits(OptionalDouble.of(-1.0), OptionalDouble.of(0.0), 0);
        assertTrue(limits.maxPerUserPerDayUsd().isEmpty());
        assertEquals(OptionalDouble.of(0.0), limits.maxPerTeamPerDayUsd());
        assertEquals(1, limits.retentionDays());
        assertTrue(new DocumentSpendLimits(OptionalDouble.of(Double.NaN), null, 35).maxPerUserPerDayUsd().isEmpty());
    }
}
