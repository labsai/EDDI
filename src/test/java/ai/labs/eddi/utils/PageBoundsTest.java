/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page ceiling every listing shares (L10 of the 2026-10-15 security
 * review).
 */
class PageBoundsTest {

    @AfterEach
    void restore() {
        PageBounds.overrideMaxLimit(null);
    }

    @Test
    @DisplayName("the default ceiling is 1000, the Manager's largest descriptor page")
    void defaultCeiling() {
        assertEquals(PageBounds.DEFAULT_MAX_LIMIT, PageBounds.maxLimit());
        assertEquals(1000, PageBounds.DEFAULT_MAX_LIMIT);
    }

    @Test
    @DisplayName("a limit above the ceiling is refused with 400 and the message names the ceiling")
    void limitAboveCeiling() {
        PageBounds.overrideMaxLimit(50);

        var refused = assertThrows(BadRequestException.class, () -> PageBounds.require(0, 51));
        assertTrue(refused.getMessage().contains("50"), refused.getMessage());
        assertEquals(400, refused.getResponse().getStatus());
        assertEquals("application/json", refused.getResponse().getMediaType().toString());
        assertTrue(refused.getResponse().getEntity().toString().contains("50"), "the body carries the message as JSON");
        assertThrows(BadRequestException.class, () -> PageBounds.requireLimit(Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("a limit at or below the ceiling, a null limit and a non-positive limit pass")
    void limitsThatPass() {
        PageBounds.overrideMaxLimit(50);

        assertDoesNotThrow(() -> PageBounds.require(0, 50));
        assertDoesNotThrow(() -> PageBounds.require(0, 1));
        assertDoesNotThrow(() -> PageBounds.require(null, null));
        // The endpoints normalise a non-positive limit to their own default.
        assertDoesNotThrow(() -> PageBounds.require(0, 0));
        assertDoesNotThrow(() -> PageBounds.require(0, -5));
    }

    @Test
    @DisplayName("a negative index is refused; zero and positive pass")
    void index() {
        assertThrows(BadRequestException.class, () -> PageBounds.require(-1, 10));
        assertDoesNotThrow(() -> PageBounds.require(0, 10));
        assertDoesNotThrow(() -> PageBounds.require(Integer.MAX_VALUE, 10));
    }
}
