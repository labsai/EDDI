/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import ai.labs.eddi.engine.attachments.AttachmentSelector;
import ai.labs.eddi.engine.documents.SelectorException.Reason;
import ai.labs.eddi.modules.ingestion.extract.document.DocumentManifest;
import ai.labs.eddi.modules.ingestion.extract.document.DocumentUnit;
import ai.labs.eddi.modules.ingestion.extract.document.SplitStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectorResolverTest {

    private static final String REF = "6560a1b2c3d4e5f6a7b8c9d0";
    private static final String SPLIT_X = "0123456789abcdef";
    private static final String SPLIT_Y = "fedcba9876543210";

    /**
     * 20 pages; u1 1-5, u2 6-12, u3 13-20 (u3 has sub-units u3.1 13-16, u3.2
     * 17-20).
     */
    private static final DocumentManifest MANIFEST = new DocumentManifest(REF, "sha", 20, SplitStrategy.OUTLINE,
            List.of(unit("u1", null, 1, 5), unit("u2", null, 6, 12), unit("u3", null, 13, 20), unit("u3.1", "u3", 13, 16),
                    unit("u3.2", "u3", 17, 20)),
            List.of(), List.of());

    private static DocumentUnit unit(String id, String parent, int first, int last) {
        return new DocumentUnit(id, "t", parent == null ? 0 : 1, parent, first, last, 100, 0, 0, 0);
    }

    /** Counts how often the manifest is asked for. */
    private static final class Facts implements SelectorResolver.DocumentFacts {
        final AtomicInteger manifests = new AtomicInteger();

        @Override
        public int pageCount() {
            return MANIFEST.pageCount();
        }

        @Override
        public DocumentManifest manifest() {
            manifests.incrementAndGet();
            return MANIFEST;
        }
    }

    private static AttachmentSelector resolve(String unit, String pages, String carried) {
        return SelectorResolver.resolve(REF, unit, pages, carried, SPLIT_X, new Facts());
    }

    private static Reason reasonOf(Runnable r) {
        return assertThrows(SelectorException.class, r::run).reason();
    }

    @Test
    void nothingRequested_isTheWholeFile() {
        assertTrue(resolve(null, null, null).isWholeFile());
        assertTrue(resolve(" ", "", null).isWholeFile());
        assertTrue(resolve(null, null, SPLIT_Y).isWholeFile(), "a split hash alone names no part");
    }

    @Test
    void unitOnly_becomesItsPages_underTheReceivingSplit() {
        var s = resolve("u2", null, null);
        assertEquals(new AttachmentSelector(REF, "6-12", "u2", SPLIT_X), s);
        assertEquals("13-16", resolve("u3.1", null, null).pages());
        assertEquals("13-20", resolve("u3", null, null).pages(), "a parent unit spans its children");
    }

    @Test
    void unitWithItsOwnSplitHash_resolves() {
        assertEquals("6-12", resolve("u2", null, SPLIT_X).pages());
    }

    @Test
    void pagesGiven_areKept_andTheUnitIsNeverReResolved() {
        var facts = new Facts();
        // "u2" is 6-12 in the receiving split, but the sender said 13-20: the pages
        // win.
        var s = SelectorResolver.resolve(REF, "u2", "13-20", SPLIT_Y, SPLIT_X, facts);

        assertEquals(new AttachmentSelector(REF, "13-20", "u2", SPLIT_Y), s);
        assertEquals(0, facts.manifests.get(), "pages are never re-derived from the label");
    }

    @Test
    void pagesAlone_carryNoLabel() {
        assertEquals(new AttachmentSelector(REF, "4-9", null, null), resolve(null, " 4 - 9 ", SPLIT_Y));
    }

    @Test
    void unitWithAForeignSplit_andNoPages_isAnError() {
        var facts = new Facts();
        var e = assertThrows(SelectorException.class, () -> SelectorResolver.resolve(REF, "u2", null, SPLIT_Y, SPLIT_X, facts));
        assertEquals(Reason.FOREIGN_SPLIT, e.reason());
        assertTrue(e.getMessage().contains("send pages"));
        assertEquals(0, facts.manifests.get(), "never a guess under the receiving split");
    }

    @Test
    void unknownUnit_isAnError_neverTheWholeFile() {
        var e = assertThrows(SelectorException.class, () -> resolve("u9", null, null));
        assertEquals(Reason.UNKNOWN_UNIT, e.reason());
        assertEquals("this file has no chapter u9", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "u", "3", "U3", "u3.", "u3..1", "u1 ; drop", "u1234567"})
    void malformedUnit_isAnError(String unit) {
        assertEquals(Reason.UNKNOWN_UNIT, reasonOf(() -> resolve(unit, null, null)));
    }

    @Test
    void malformedSplitHash_isAnError() {
        assertEquals(Reason.FOREIGN_SPLIT, reasonOf(() -> resolve("u1", null, "not-a-hash")));
        assertEquals(Reason.FOREIGN_SPLIT, reasonOf(() -> resolve("u1", "1-2", "0123456789ABCDEF")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"4", "4-", "-9", "a-b", "9-4", "0-3", "4-9-11", "4..9", "p4-9"})
    void malformedPages_areAnError(String pages) {
        assertEquals(Reason.BAD_PAGES, reasonOf(() -> resolve(null, pages, null)));
    }

    @Test
    void pagesOutsideTheDocument_areAnError() {
        var e = assertThrows(SelectorException.class, () -> resolve(null, "19-21", null));
        assertEquals(Reason.PAGES_OUT_OF_RANGE, e.reason());
        assertEquals("pages 19-21 are outside this file, which has 20 pages", e.getMessage());
        assertEquals("20-20", resolve(null, "20-20", null).pages(), "the last page is inside");
    }

    @Test
    void messages_neverNameTheReference() {
        for (Runnable r : List.<Runnable>of(() -> resolve("u9", null, null), () -> resolve(null, "30-31", null),
                () -> resolve("u1", null, SPLIT_Y), () -> resolve(null, "x", null))) {
            assertFalse(assertThrows(SelectorException.class, r::run).getMessage().contains(REF));
        }
    }

    @Test
    void parsePages_canonicalizes() {
        assertEquals("4-9", SelectorResolver.parsePages(" 4 -9").text());
    }
}
