/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.IAttachmentStore.OwnerRef;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuotaModeTest {

    private static final String CONVERSATION = "6aba70011a25f8be7920e301";
    private static final OwnerRef OWNER = new OwnerRef(OwnerScope.CONVERSATION, CONVERSATION, null);

    @Test
    void standardIsStoredAsNothingSoEveryOlderRowReadsAsStandard() {
        assertNull(QuotaMode.STANDARD.stored());
        assertEquals("LARGE_PER_USER", QuotaMode.LARGE_PER_USER.stored());
        assertEquals(QuotaMode.STANDARD, QuotaMode.fromStored(null));
        assertEquals(QuotaMode.STANDARD, QuotaMode.fromStored("SOMETHING_NEWER"));
        assertEquals(QuotaMode.LARGE_PER_USER, QuotaMode.fromStored("LARGE_PER_USER"));
    }

    @Test
    void aLargeReservationNeedsAUser() {
        assertThrows(IllegalArgumentException.class,
                () -> QuotaReservation.validateRequest(OWNER, CONVERSATION, null, 10, 1, Duration.ofMinutes(1), QuotaMode.LARGE_PER_USER));
        assertDoesNotThrow(
                () -> QuotaReservation.validateRequest(OWNER, CONVERSATION, "user-1", 10, 1, Duration.ofMinutes(1), QuotaMode.LARGE_PER_USER));
        assertDoesNotThrow(() -> QuotaReservation.validateRequest(OWNER, CONVERSATION, null, 10, 1, Duration.ofMinutes(1), QuotaMode.STANDARD));
    }

    @Test
    void theModeAwareCheckKeepsEveryCheckOfTheShortOne() {
        assertThrows(IllegalArgumentException.class,
                () -> QuotaReservation.validateRequest(OWNER, CONVERSATION, "user-1", -1, 1, Duration.ofMinutes(1), QuotaMode.STANDARD));
        assertThrows(IllegalArgumentException.class,
                () -> QuotaReservation.validateRequest(OWNER, CONVERSATION, "user-1", 10, 1, Duration.ofMinutes(1), null));
    }
}
