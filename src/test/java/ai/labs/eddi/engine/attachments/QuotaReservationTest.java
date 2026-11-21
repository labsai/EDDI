/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentQuotaExceededException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.OwnerRef;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The checks every store makes on a reservation request, and the quota rule for
 * several files at once.
 */
class QuotaReservationTest {

    private static final String CONVERSATION = "6aba70011a25f8be7920e001";
    private static final OwnerRef OWNER = new OwnerRef(OwnerScope.CONVERSATION, CONVERSATION, null);
    private static final Duration TTL = Duration.ofMinutes(5);

    @Test
    void aConversationReservingInItsOwnBucketIsAccepted() {
        assertDoesNotThrow(() -> QuotaReservation.validateRequest(OWNER, CONVERSATION, 10, 1, TTL));
        assertDoesNotThrow(() -> QuotaReservation.validateRequest(OWNER, CONVERSATION, 0, 1, QuotaReservation.MAX_TTL));
        assertDoesNotThrow(() -> QuotaReservation.validateRequest(OWNER, CONVERSATION, 10, 0, Duration.ofMillis(1)));
    }

    @Test
    void onlyConversationOwnersReserveUntilTheLibraryBucketsExist() {
        for (OwnerScope scope : new OwnerScope[]{OwnerScope.USER, OwnerScope.TEAM}) {
            assertThrows(IllegalArgumentException.class,
                    () -> QuotaReservation.validateRequest(new OwnerRef(scope, CONVERSATION, null), CONVERSATION, 10, 1, TTL), scope.name());
        }
        assertThrows(IllegalArgumentException.class, () -> QuotaReservation.validateRequest(null, CONVERSATION, 10, 1, TTL));
    }

    @Test
    void theReservationIsHeldInTheOwnersOwnConversation() {
        assertThrows(IllegalArgumentException.class, () -> QuotaReservation.validateRequest(OWNER, "6aba70011a25f8be7920e002", 10, 1, TTL));
        assertThrows(IllegalArgumentException.class, () -> QuotaReservation.validateRequest(OWNER, null, 10, 1, TTL));
    }

    @Test
    void amountsMustBeSomethingAndNeverNegative() {
        assertThrows(IllegalArgumentException.class, () -> QuotaReservation.validateRequest(OWNER, CONVERSATION, 0, 0, TTL));
        assertThrows(IllegalArgumentException.class, () -> QuotaReservation.validateRequest(OWNER, CONVERSATION, -1, 1, TTL));
        assertThrows(IllegalArgumentException.class, () -> QuotaReservation.validateRequest(OWNER, CONVERSATION, 1, -1, TTL));
    }

    @Test
    void theLifetimeIsBetweenOneMillisecondAndTheMaximum() {
        assertThrows(IllegalArgumentException.class, () -> QuotaReservation.validateRequest(OWNER, CONVERSATION, 10, 1, null));
        assertThrows(IllegalArgumentException.class, () -> QuotaReservation.validateRequest(OWNER, CONVERSATION, 10, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> QuotaReservation.validateRequest(OWNER, CONVERSATION, 10, 1, Duration.ofNanos(999_999)));
        assertThrows(IllegalArgumentException.class,
                () -> QuotaReservation.validateRequest(OWNER, CONVERSATION, 10, 1, Duration.ofMinutes(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> QuotaReservation.validateRequest(OWNER, CONVERSATION, 10, 1, QuotaReservation.MAX_TTL.plusMillis(1)));
    }

    @Test
    void severalFilesAtOnceAreCheckedAgainstTheFileLimit() throws Exception {
        String scope = AttachmentQuotaExceededException.SCOPE_CONVERSATION;
        AttachmentQuotas.check(scope, 3, 0, 2, 0, 5, 0);

        var refused = assertThrows(AttachmentQuotaExceededException.class, () -> AttachmentQuotas.check(scope, 3, 0, 3, 0, 5, 0));
        assertEquals(scope, refused.getScope());
        assertDoesNotThrow(() -> AttachmentQuotas.check(scope, 5, 0, 0, 10, 5, 0), "bytes only: the file limit does not apply");
        assertThrows(AttachmentQuotaExceededException.class, () -> AttachmentQuotas.check(scope, 5, 0, 1, 0, 5, 0), "one more file than allowed");
    }

    @Test
    void theBytesOfAReservationAreCheckedAgainstTheByteLimit() throws Exception {
        String scope = AttachmentQuotaExceededException.SCOPE_USER;
        AttachmentQuotas.check(scope, 0, 60, 0, 40, 0, 100);

        assertThrows(AttachmentQuotaExceededException.class, () -> AttachmentQuotas.check(scope, 0, 60, 0, 41, 0, 100));
    }
}
