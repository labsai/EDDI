/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HmacSignaturesTest {

    private static final long NOW = 1_760_000_000L;
    private static final long WINDOW = 300;

    @Test
    void withinWindowEitherDirectionIsFresh() {
        assertTrue(HmacSignatures.isFresh(NOW, NOW, WINDOW));
        assertTrue(HmacSignatures.isFresh(NOW - WINDOW, NOW, WINDOW));
        assertTrue(HmacSignatures.isFresh(NOW + WINDOW, NOW, WINDOW));
        assertFalse(HmacSignatures.isFresh(NOW - WINDOW - 1, NOW, WINDOW));
        assertFalse(HmacSignatures.isFresh(NOW + WINDOW + 1, NOW, WINDOW));
    }

    @Test
    void extremeTimestampsDoNotOverflowIntoFresh() {
        // now - Long.MIN_VALUE overflows; Math.abs of the wrapped value is negative
        // and used to pass "<= window".
        assertFalse(HmacSignatures.isFresh(Long.MIN_VALUE, NOW, WINDOW));
        assertFalse(HmacSignatures.isFresh(Long.MIN_VALUE + 1, NOW, WINDOW));
        assertFalse(HmacSignatures.isFresh(Long.MIN_VALUE + NOW, NOW, WINDOW));
        assertFalse(HmacSignatures.isFresh(Long.MAX_VALUE, NOW, WINDOW));
        assertFalse(HmacSignatures.isFresh(Long.MAX_VALUE, -NOW, WINDOW));
        assertFalse(HmacSignatures.isFresh(Long.MIN_VALUE, Long.MAX_VALUE, WINDOW));
    }

    @Test
    void negativeWindowIsNeverFresh() {
        assertFalse(HmacSignatures.isFresh(NOW, NOW, -1));
    }

    @Test
    void constantTimeEqualsHandlesNull() {
        assertTrue(HmacSignatures.constantTimeEquals("abc", "abc"));
        assertFalse(HmacSignatures.constantTimeEquals("abc", null));
        assertFalse(HmacSignatures.constantTimeEquals((byte[]) null, new byte[0]));
    }

    @Test
    void blankSecretIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> HmacSignatures.hmacSha256(" ", new byte[0]));
    }
}
