/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.AttachmentLockOrder.Lock;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentQuotaExceededException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.OwnerRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LibraryStoreSupport")
class LibraryStoreSupportTest {

    private static final LibraryStoreSupport.Limits LIMITS = new LibraryStoreSupport.Limits(2, 100, 365, 3);

    @Nested
    @DisplayName("grant eviction")
    class Eviction {

        @Test
        void withinTheCapNothingIsEvicted() {
            assertEquals(List.of(), LibraryStoreSupport.grantsToEvict(List.of("a"), List.of("a", "b", "c"), "c", 3));
        }

        @Test
        void pastTheCapTheOldestRowsGoButNeverTheGrantJustWritten() {
            assertEquals(List.of("a"), LibraryStoreSupport.grantsToEvict(List.of(), List.of("a", "b", "c", "d"), "d", 3));
            assertEquals(List.of("b"), LibraryStoreSupport.grantsToEvict(List.of(), List.of("a", "b", "c", "d"), "a", 3),
                    "the kept grant is skipped, the next oldest goes");
            assertEquals(List.of("a", "b", "c"), LibraryStoreSupport.grantsToEvict(List.of(), List.of("a", "b", "c", "d"), "d", 1));
        }

        @Test
        void aLegacyOnlyGrantGoesBeforeAnyRowAndBothShapesCountOnce() {
            assertEquals(List.of("x"), LibraryStoreSupport.grantsToEvict(List.of("a", "x"), List.of("a", "b", "c"), "c", 3),
                    "4 distinct grantees; the one of unknown age goes first");
            assertEquals(List.of(), LibraryStoreSupport.grantsToEvict(List.of("a", "b"), List.of("a", "b"), "b", 2),
                    "a grantee in both shapes is one grant");
        }

        @Test
        void aCapOfZeroDisablesEviction() {
            assertEquals(List.of(), LibraryStoreSupport.grantsToEvict(List.of("x"), List.of("a", "b"), "b", 0));
        }
    }

    @Nested
    @DisplayName("quota")
    class Quota {

        @Test
        void aDocumentThatFitsExactlyIsAccepted() {
            assertDoesNotThrow(() -> LibraryStoreSupport.checkQuota(1, 60, 1, 40, LIMITS));
        }

        @Test
        void theDocumentLimitRefusesWithTheNumbers() {
            var refused = assertThrows(AttachmentQuotaExceededException.class, () -> LibraryStoreSupport.checkQuota(2, 0, 1, 1, LIMITS));
            assertEquals(AttachmentQuotaExceededException.SCOPE_LIBRARY, refused.getScope());
            assertTrue(refused.getMessage().contains("2 of 2 documents"), refused.getMessage());
        }

        @Test
        void theByteLimitRefusesWithTheNumbers() {
            var refused = assertThrows(AttachmentQuotaExceededException.class, () -> LibraryStoreSupport.checkQuota(0, 60, 1, 41, LIMITS));
            assertEquals(AttachmentQuotaExceededException.SCOPE_LIBRARY, refused.getScope());
            assertTrue(refused.getMessage().contains("60 + 41 bytes") && refused.getMessage().contains("100"), refused.getMessage());
        }

        @Test
        void aMediaViewAddsBytesButNoDocument() {
            assertDoesNotThrow(() -> LibraryStoreSupport.checkQuota(2, 60, 0, 40, LIMITS), "a full count does not refuse a view");
            assertThrows(AttachmentQuotaExceededException.class, () -> LibraryStoreSupport.checkQuota(2, 60, 0, 41, LIMITS));
        }

        @Test
        void zeroDisablesALimitAndNothingOverflows() {
            var unlimited = new LibraryStoreSupport.Limits(0, 0, 365, 3);
            assertDoesNotThrow(() -> LibraryStoreSupport.checkQuota(Long.MAX_VALUE, Long.MAX_VALUE, 1, Long.MAX_VALUE, unlimited));
            assertThrows(AttachmentQuotaExceededException.class,
                    () -> LibraryStoreSupport.checkQuota(0, 1, 1, Long.MAX_VALUE, LIMITS), "no overflow past the limit");
        }
    }

    @Nested
    @DisplayName("slugs")
    class Slugs {

        @Test
        void theWantedSlugWhenFreeElseTheFirstFreeSuffix() throws AttachmentStoreException {
            assertEquals("report", LibraryStoreSupport.uniqueSlug("report", slug -> false));
            Set<String> taken = Set.of("report", "report-2");
            assertEquals("report-3", LibraryStoreSupport.uniqueSlug("report", taken::contains));
        }

        @Test
        void theSearchIsBounded() {
            List<String> tried = new ArrayList<>();
            assertThrows(AttachmentStoreException.class, () -> LibraryStoreSupport.uniqueSlug("report", slug -> tried.add(slug)));
            assertEquals(LibraryStoreSupport.MAX_SLUG_ATTEMPTS, tried.size());
        }
    }

    @Nested
    @DisplayName("owners and locks")
    class Owners {

        @Test
        void onlyAUserLibraryIsAnOwnerInThisRelease() {
            assertDoesNotThrow(() -> LibraryStoreSupport.requireLibraryOwner(new OwnerRef(OwnerScope.USER, "space-1", "u")));
            assertThrows(IllegalArgumentException.class,
                    () -> LibraryStoreSupport.requireLibraryOwner(new OwnerRef(OwnerScope.TEAM, "space-1", "u")));
            assertThrows(IllegalArgumentException.class,
                    () -> LibraryStoreSupport.requireLibraryOwner(new OwnerRef(OwnerScope.CONVERSATION, "6aba70011a25f8be7920e001", "u")));
            assertThrows(IllegalArgumentException.class, () -> LibraryStoreSupport.requireLibraryOwner(new OwnerRef(OwnerScope.USER, " ", "u")));
            assertThrows(IllegalArgumentException.class, () -> LibraryStoreSupport.requireLibraryOwner(null));
        }

        @Test
        void aPersonalLibraryLocksInTheUserPlaceUnderAnIdOfItsOwn() {
            Lock lock = LibraryStoreSupport.bucketLock(OwnerScope.USER, "space-1");
            assertEquals(new Lock(AttachmentLockOrder.Scope.USER, "library:space-1"), lock);
            assertEquals(new Lock(AttachmentLockOrder.Scope.TEAM, "space-2"), LibraryStoreSupport.bucketLock(OwnerScope.TEAM, "space-2"));
        }

        @Test
        void twoUserPlaceLocksAreTakenInTheOrderOfTheirIds() {
            Lock library = LibraryStoreSupport.bucketLock(OwnerScope.USER, "space-1");
            Lock user = new Lock(AttachmentLockOrder.Scope.USER, "user-1");
            Lock source = new Lock(AttachmentLockOrder.Scope.SOURCE, "root");
            Lock conversation = new Lock(AttachmentLockOrder.Scope.CONVERSATION, "c");
            List<Lock> expected = List.of(conversation, library, user, source);
            assertEquals(expected, AttachmentLockOrder.ordered(List.of(source, user, library, conversation)));
            assertEquals(expected, AttachmentLockOrder.ordered(List.of(library, source, conversation, user)), "whatever order they come in");
        }
    }

    @Nested
    @DisplayName("paging")
    class Paging {

        @Test
        void aPageIsBoundedByTheListingCeiling() {
            assertEquals(0, LibraryStoreSupport.pageOffset(-5));
            assertEquals(7, LibraryStoreSupport.pageOffset(7));
            assertEquals(10, LibraryStoreSupport.pageLimit(10));
            assertEquals(1000, LibraryStoreSupport.pageLimit(0), "zero reads as the ceiling");
            assertEquals(1000, LibraryStoreSupport.pageLimit(Integer.MAX_VALUE));
        }
    }

    @Nested
    @DisplayName("LibraryMeta")
    class Meta {

        @Test
        void boundsItsFields() {
            assertDoesNotThrow(() -> new LibraryMeta("Annual report", "annual-report-2026", "agent-1", 0));
            assertThrows(IllegalArgumentException.class, () -> new LibraryMeta(" ", "a", null, null));
            assertThrows(IllegalArgumentException.class, () -> new LibraryMeta("x".repeat(201), "a", null, null));
            for (String slug : List.of("", "Upper", "trailing-", "-leading", "spa ce", "dots.no", "x".repeat(81))) {
                assertThrows(IllegalArgumentException.class, () -> new LibraryMeta("t", slug, null, null), slug);
            }
            assertThrows(IllegalArgumentException.class, () -> new LibraryMeta("t", "a", " ", null));
            assertThrows(IllegalArgumentException.class, () -> new LibraryMeta("t", "a", null, -1));
        }

        @Test
        void suffixesFromTheSecond() {
            assertEquals("report", LibraryMeta.suffixed("report", 1));
            assertEquals("report-2", LibraryMeta.suffixed("report", 2));
        }
    }
}
