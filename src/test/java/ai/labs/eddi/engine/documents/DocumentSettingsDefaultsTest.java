/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import ai.labs.eddi.engine.attachments.DerivedCaps;
import ai.labs.eddi.engine.attachments.LibraryStoreSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    @Test
    @DisplayName("WP-0.1 bounds no key yet")
    void noBoundsYet() {
        assertTrue(DocumentBounds.BOUND_PROPERTIES.isEmpty(), DocumentBounds.BOUND_PROPERTIES.toString());
        assertTrue(DocumentBounds.current().boundFor("sharing.maxDepth").isEmpty());
    }

    // --- WP-0.1: later packages append one block per property below ---

    // --- WP-2a.2 ---

    @Test
    @DisplayName("derived-blob caps: the design's defaults, from the deployment's configuration")
    void derivedCaps() {
        var expected = new DerivedCaps.Limits(200, 52_428_800L, 20, 52_428_800L, 209_715_200L);
        assertEquals(expected, DocumentSettings.none().derivedCaps());
        assertEquals(expected, new DocumentSettings().derivedCaps(), "no property set in the test configuration");
    }

    // --- WP-5a.1 ---

    @Test
    @DisplayName("library store limits: the design's defaults, from the deployment's configuration")
    void libraryLimits() {
        var expected = new LibraryStoreSupport.Limits(50, 2_147_483_648L, 365, 1000);
        assertEquals(expected, DocumentSettings.none().libraryLimits());
        assertEquals(expected, new DocumentSettings().libraryLimits(), "no property set in the test configuration");
    }
}
