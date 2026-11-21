/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.Derivation.Kind;
import ai.labs.eddi.engine.attachments.DerivedCaps.Category;
import ai.labs.eddi.engine.attachments.DerivedCaps.Existing;
import ai.labs.eddi.engine.attachments.DerivedCaps.Limits;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentDerivedCapException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DerivedCaps — per-source caps on derived blobs")
class DerivedCapsTest {

    private static final Instant T0 = Instant.parse("2026-11-21T10:00:00Z");

    private static Existing blob(String ref, Kind kind, long bytes) {
        return new Existing(ref, kind, bytes, T0);
    }

    private static Existing slice(String ref, long bytes, Instant accessed) {
        return new Existing(ref, Kind.PDF_SLICE, bytes, accessed);
    }

    private static List<Existing> many(Kind kind, int count, long bytesEach) {
        List<Existing> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(blob(kind.name() + i, kind, bytesEach));
        }
        return list;
    }

    @Nested
    @DisplayName("which budget a kind draws on")
    class Categories {

        private static final Set<Kind> MEDIA = EnumSet.of(Kind.IMAGE, Kind.TABLE, Kind.AUDIO, Kind.EMBEDDED);

        @ParameterizedTest
        @EnumSource(Kind.class)
        void onlyMediaCountTowardTheOwnersQuota(Kind kind) {
            assertEquals(MEDIA.contains(kind), DerivedCaps.countsTowardOwnerQuota(kind), kind.name());
        }

        @Test
        void everyKindHasABudget() {
            assertEquals(Category.SLICE, DerivedCaps.categoryOf(Kind.PDF_SLICE));
            for (Kind kind : EnumSet.of(Kind.MANIFEST, Kind.MANIFEST_PART, Kind.TEXT_PART, Kind.SUMMARY, Kind.DESCRIPTION)) {
                assertEquals(Category.TEXT, DerivedCaps.categoryOf(kind), kind.name());
            }
            for (Kind kind : MEDIA) {
                assertEquals(Category.MEDIA, DerivedCaps.categoryOf(kind), kind.name());
            }
        }

        @Test
        void aNullKindCountsTowardNothing() {
            assertFalse(DerivedCaps.countsTowardOwnerQuota(null));
        }
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        void matchTheDesignTable() {
            assertEquals(new Limits(200, 52_428_800L, 20, 52_428_800L, 209_715_200L), Limits.defaults());
        }
    }

    @Nested
    @DisplayName("media: count and bytes, shared by IMAGE/TABLE/AUDIO/EMBEDDED")
    class Media {

        private final Limits limits = new Limits(3, 100, 0, 0, 0);

        @Test
        void theLastSlotFitsAndTheNextIsRefusedByName() throws Exception {
            List<Existing> two = List.of(blob("a", Kind.IMAGE, 10), blob("b", Kind.TABLE, 10));
            assertEquals(List.of(), DerivedCaps.check(Kind.AUDIO, 10, two, limits));

            List<Existing> three = List.of(blob("a", Kind.IMAGE, 10), blob("b", Kind.TABLE, 10), blob("c", Kind.EMBEDDED, 10));
            var refused = assertThrows(AttachmentDerivedCapException.class, () -> DerivedCaps.check(Kind.IMAGE, 1, three, limits));
            assertEquals("max-per-source", refused.getCap());
        }

        @Test
        void bytesUpToTheLimitFitAndOneMoreDoesNot() throws Exception {
            List<Existing> used = List.of(blob("a", Kind.IMAGE, 60));
            assertEquals(List.of(), DerivedCaps.check(Kind.IMAGE, 40, used, limits));
            var refused = assertThrows(AttachmentDerivedCapException.class, () -> DerivedCaps.check(Kind.IMAGE, 41, used, limits));
            assertEquals("max-total-bytes-per-source", refused.getCap());
        }

        @Test
        void textAndSlicesDoNotUseTheMediaBudget() {
            List<Existing> lineage = new ArrayList<>(many(Kind.TEXT_PART, 10, 1_000));
            lineage.addAll(many(Kind.PDF_SLICE, 10, 1_000));
            assertDoesNotThrow(() -> DerivedCaps.check(Kind.IMAGE, 100, lineage, limits));
        }

        @Test
        void zeroDisablesALimit() {
            assertDoesNotThrow(() -> DerivedCaps.check(Kind.IMAGE, 1_000_000, many(Kind.IMAGE, 5_000, 1_000), new Limits(0, 0, 0, 0, 0)));
        }
    }

    @Nested
    @DisplayName("text kinds: bytes only")
    class Text {

        private final Limits limits = new Limits(1, 1, 0, 0, 100);

        @Test
        void haveNoCountAndTheirOwnByteCap() throws Exception {
            List<Existing> lineage = many(Kind.TEXT_PART, 9, 10);
            assertEquals(List.of(), DerivedCaps.check(Kind.SUMMARY, 10, lineage, limits), "500 parts would be fine, only bytes count");

            var refused = assertThrows(AttachmentDerivedCapException.class, () -> DerivedCaps.check(Kind.MANIFEST, 11, lineage, limits));
            assertEquals("max-text-bytes-per-source", refused.getCap());
        }

        @Test
        void mediaBytesDoNotUseTheTextBudget() {
            assertDoesNotThrow(() -> DerivedCaps.check(Kind.TEXT_PART, 100, many(Kind.IMAGE, 1, 1_000_000), limits));
        }
    }

    @Nested
    @DisplayName("slices: least recently used evicted, never refused for room")
    class Slices {

        @Test
        void withRoomNothingIsEvicted() throws Exception {
            Limits limits = new Limits(0, 0, 3, 100, 0);
            assertEquals(List.of(), DerivedCaps.check(Kind.PDF_SLICE, 10,
                    List.of(slice("a", 10, T0), slice("b", 10, T0.plusSeconds(1))), limits));
        }

        @Test
        void atTheCountTheLeastRecentlyAccessedGoes() throws Exception {
            Limits limits = new Limits(0, 0, 2, 0, 0);
            List<Existing> lineage = List.of(slice("recent", 10, T0.plusSeconds(60)), slice("old", 10, T0), slice("middle", 10, T0.plusSeconds(30)));

            assertEquals(List.of("old", "middle"), DerivedCaps.check(Kind.PDF_SLICE, 10, lineage, limits));
        }

        @Test
        void neverAccessedCountsAsOldest() throws Exception {
            Limits limits = new Limits(0, 0, 1, 0, 0);
            List<Existing> lineage = List.of(slice("touched", 10, T0), slice("untouched", 10, null));

            assertEquals(List.of("untouched", "touched"), DerivedCaps.check(Kind.PDF_SLICE, 10, lineage, limits));
        }

        @Test
        void bytesEvictUntilTheNewSliceFits() throws Exception {
            Limits limits = new Limits(0, 0, 0, 100, 0);
            List<Existing> lineage = List.of(slice("a", 40, T0), slice("b", 40, T0.plusSeconds(1)), slice("c", 10, T0.plusSeconds(2)));

            assertEquals(List.of("a"), DerivedCaps.check(Kind.PDF_SLICE, 50, lineage, limits), "90 - 40 + 50 = 100 fits");
            assertEquals(List.of("a", "b"), DerivedCaps.check(Kind.PDF_SLICE, 51, lineage, limits));
        }

        @Test
        void aSliceLargerThanTheWholeBudgetIsRefused() {
            Limits limits = new Limits(0, 0, 20, 100, 0);
            var refused = assertThrows(AttachmentDerivedCapException.class,
                    () -> DerivedCaps.check(Kind.PDF_SLICE, 101, List.of(slice("a", 1, T0)), limits));
            assertEquals("max-slice-bytes-per-source", refused.getCap());
        }

        @Test
        void otherKindsAreNeverEvicted() throws Exception {
            Limits limits = new Limits(0, 0, 1, 0, 0);
            List<Existing> lineage = List.of(blob("image", Kind.IMAGE, 10), blob("text", Kind.TEXT_PART, 10), slice("s", 10, T0));

            assertEquals(List.of("s"), DerivedCaps.check(Kind.PDF_SLICE, 10, lineage, limits));
        }

        @Test
        void slicesDoNotUseTheMediaOrTextBudget() {
            assertDoesNotThrow(() -> DerivedCaps.check(Kind.PDF_SLICE, 10, many(Kind.IMAGE, 5, 10), new Limits(1, 1, 5, 0, 1)));
            assertTrue(assertDoesNotThrow(() -> DerivedCaps.check(Kind.TEXT_PART, 1, many(Kind.PDF_SLICE, 5, 10), new Limits(1, 1, 1, 1, 1)))
                    .isEmpty());
        }
    }
}
