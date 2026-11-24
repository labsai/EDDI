/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.memory.model.Attachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The listing's recorded derived entries: parts and views a conversation's
 * tools recorded, listed only when the store confirms the root and the root is
 * listed and not withheld. Ids are 24-hex.
 */
class ConversationListingRecordedTest {

    private static final String A = "64b7f0c2a1d3e4f5a6b7c8d9";
    private static final String STRANGER = "64b7f0c2a1d3e4f5a6b7c8db";
    private static final String PDF = "6560a1b2c3d4e5f6a7b8c9d0";
    private static final String FOREIGN = "6560a1b2c3d4e5f6a7b8c9d1";
    private static final String VIEW = "6560a1b2c3d4e5f6a7b8c9d3";

    private InMemoryAttachmentStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryAttachmentStore().put(PDF, "report.pdf", "application/pdf", 1200, A)
                .put(FOREIGN, "theirs.pdf", "application/pdf", 10, STRANGER)
                .putDerived(VIEW, "figure-1.png", "image/png", PDF, Derivation.Kind.IMAGE);
    }

    private static Attachment recorded(String ref, String pages, String claimedRoot) {
        Attachment a = new Attachment(pages != null ? "application/pdf" : "image/png", pages != null ? "report.pdf" : "figure-1.png", 7,
                ref);
        if (pages != null) {
            a.getMetadata().putAll(new AttachmentSelector(ref, pages, "u3", "0123456789abcdef").toMetadata());
        }
        a.getMetadata().put(AttachmentSelector.META_DERIVED, "true");
        a.getMetadata().put(AttachmentSelector.META_ROOT, claimedRoot);
        return a;
    }

    @Test
    void recordedParts_andViews_areListedBesideTheirRoot() {
        ConversationListing l = ConversationListing.of(A, store, Set.of(), List.of(recorded(PDF, "12-19", PDF), recorded(VIEW, null, PDF)));

        assertEquals(List.of("figure-1.png", "report.pdf", "report.pdf — u3, p.12–19"), l.displayNames());
        ConversationListing.Entry part = l.entries().get(2);
        assertTrue(part.derived());
        assertEquals(PDF, part.rootRef());
        assertEquals(1200, part.sizeBytes(), "a part has its root's size and type");
        ConversationListing.Entry view = l.entries().get(0);
        assertEquals(PDF, view.rootRef(), "a view's root is decided by the store");
        assertEquals(VIEW, view.selector().storageRef());
    }

    @Test
    void aRecordedRootClaim_isNotTrusted() {
        // The step claims the view belongs to the conversation's own PDF, but the view
        // is the stranger's file: the store's answer wins, and A cannot read it.
        ConversationListing l = ConversationListing.of(A, store, Set.of(), List.of(recorded(FOREIGN, "1-2", PDF)));

        assertEquals(List.of("report.pdf"), l.displayNames());
    }

    @Test
    void aWithheldRoot_takesItsPartsAndViewsWithIt() {
        ConversationListing l = ConversationListing.of(A, store, Set.of(PDF), List.of(recorded(PDF, "12-19", PDF), recorded(VIEW, null, PDF)));

        assertTrue(l.isEmpty());
    }

    @Test
    void unmarkedEntries_andDuplicates_areNotListedTwice() {
        Attachment unmarked = recorded(PDF, "1-2", PDF);
        unmarked.getMetadata().remove(AttachmentSelector.META_DERIVED);

        ConversationListing l = ConversationListing.of(A, store, Set.of(),
                List.of(unmarked, recorded(PDF, "12-19", PDF), recorded(PDF, "12-19", PDF)));

        assertEquals(2, l.entries().size());
    }

    @Test
    void entryForRoot_neverReturnsAPart() {
        ConversationListing l = ConversationListing.of(A, store, Set.of(), List.of(recorded(PDF, "1-1", PDF)));

        assertFalse(l.entryForRoot(PDF).orElseThrow().derived());
    }

    @Test
    void partName_formats() {
        assertEquals("a.pdf — u3, p.4–9", ConversationListing.partName("a.pdf", new AttachmentSelector("r", "4-9", "u3", "s")));
        assertEquals("a.pdf — p.4–9", ConversationListing.partName("a.pdf", new AttachmentSelector("r", "4-9", null, null)));
        assertEquals("a.pdf", ConversationListing.partName("a.pdf", AttachmentSelector.wholeFile("r")));
        assertEquals(ConversationListing.UNNAMED + " — u1", ConversationListing.partName(" ", new AttachmentSelector("r", null, "u1", null)));
    }
}
