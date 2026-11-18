/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.memory.model.Attachment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationListingTest {

    private static final String SOURCE = "64b7f0c2a1d3e4f5a6b7c8d9";
    private static final String OTHER = "64b7f0c2a1d3e4f5a6b7c8da";
    private static final String REF_A = "6560a1b2c3d4e5f6a7b8c9d0";
    private static final String REF_B = "6560a1b2c3d4e5f6a7b8c9d1";
    private static final String REF_C = "6560a1b2c3d4e5f6a7b8c9d2";

    @Test
    void listsOwnedAndGrantedRoots_inStoreOrder() throws Exception {
        var store = new InMemoryAttachmentStore().put(REF_A, "a.pdf", "application/pdf", 10, SOURCE).put(REF_B, "b.png",
                "image/png", 20, OTHER);
        store.grantAccess(REF_B, SOURCE);

        var listing = ConversationListing.of(SOURCE, store, Set.of(), List.of());

        assertEquals(List.of("a.pdf", "b.png"), listing.displayNames());
        var first = listing.entries().get(0);
        assertEquals(AttachmentSelector.wholeFile(REF_A), first.selector());
        assertEquals("application/pdf", first.mimeType());
        assertEquals(10, first.sizeBytes());
        assertFalse(first.derived());
        assertEquals(REF_A, first.rootRef());
    }

    @Test
    void foreignRoots_areNotListed() {
        var store = new InMemoryAttachmentStore().put(REF_A, "a.pdf", "application/pdf", 10, SOURCE).put(REF_B, "secret.pdf",
                "application/pdf", 20, OTHER);

        var listing = ConversationListing.of(SOURCE, store, Set.of(), List.of());

        assertEquals(List.of("a.pdf"), listing.displayNames());
        assertTrue(listing.entryForRoot(REF_B).isEmpty());
    }

    @Test
    void withheldRoots_areNotListed() {
        var store = new InMemoryAttachmentStore().put(REF_A, "a.pdf", "application/pdf", 10, SOURCE).put(REF_B, "refused.pdf",
                "application/pdf", 20, SOURCE);

        var listing = ConversationListing.of(SOURCE, store, Set.of(REF_B), List.of());

        assertEquals(List.of("a.pdf"), listing.displayNames());
        assertTrue(listing.entryForRoot(REF_B).isEmpty());
        assertTrue(listing.entryFor(AttachmentSelector.wholeFile(REF_B)).isEmpty());
    }

    @Test
    void unnamedFile_isShownAsUnnamed_neverByItsReference() {
        var store = new InMemoryAttachmentStore().put(REF_A, null, "application/pdf", 10, SOURCE).put(REF_B, " ", "image/png", 1,
                SOURCE);

        var listing = ConversationListing.of(SOURCE, store, null, List.of());

        assertEquals(List.of(ConversationListing.UNNAMED, ConversationListing.UNNAMED), listing.displayNames());
        assertFalse(listing.displayNames().contains(REF_A));
    }

    @Test
    void aBlobReturnedTwice_isListedOnce() {
        var store = mock(IAttachmentStore.class);
        var a = new IAttachmentStore.Attachment(REF_A, "a.pdf", "application/pdf", 10, SOURCE);
        when(store.listAccessible(SOURCE)).thenReturn(List.of(a, a));

        assertEquals(1, ConversationListing.of(SOURCE, store, Set.of(), List.of()).entries().size());
    }

    @Test
    void duplicateNames_areKept() {
        var store = new InMemoryAttachmentStore().put(REF_A, "report.pdf", "application/pdf", 10, SOURCE).put(REF_B, "report.pdf",
                "application/pdf", 20, SOURCE);

        assertEquals(List.of("report.pdf", "report.pdf"), ConversationListing.of(SOURCE, store, Set.of(), List.of()).displayNames());
    }

    @Test
    void noStoreOrNoConversation_isEmpty() {
        var store = mock(IAttachmentStore.class);
        assertTrue(ConversationListing.of(SOURCE, null, Set.of(), List.of()).isEmpty());
        assertTrue(ConversationListing.of(null, store, Set.of(), List.of()).isEmpty());
        assertTrue(ConversationListing.of(" ", store, Set.of(), List.of()).isEmpty());
        verifyNoInteractions(store);
    }

    @Test
    void failingStore_isEmpty_neverThrows() {
        var store = mock(IAttachmentStore.class);
        when(store.listAccessible(SOURCE)).thenThrow(new IllegalStateException("db down"));

        assertTrue(ConversationListing.of(SOURCE, store, Set.of(), List.of()).isEmpty());
    }

    @Test
    void recordedDerived_isNotConsultedYet() {
        var store = new InMemoryAttachmentStore().put(REF_A, "a.pdf", "application/pdf", 10, SOURCE);
        var recorded = new Attachment("application/pdf", "foreign.pdf", 5, REF_C);
        recorded.getMetadata().put(AttachmentSelector.META_DERIVED, "true");

        var listing = ConversationListing.of(SOURCE, store, Set.of(), List.of(recorded));

        assertEquals(List.of("a.pdf"), listing.displayNames());
    }

    @Test
    void entryFor_matchesTheWholeSelector() {
        var store = new InMemoryAttachmentStore().put(REF_A, "a.pdf", "application/pdf", 10, SOURCE);
        var listing = ConversationListing.of(SOURCE, store, Set.of(), List.of());

        assertTrue(listing.entryFor(AttachmentSelector.wholeFile(REF_A)).isPresent());
        assertTrue(listing.entryFor(new AttachmentSelector(REF_A, "1-2", null, null)).isEmpty());
        assertTrue(listing.entryFor(null).isEmpty());
        assertTrue(listing.entryForRoot(REF_A).isPresent());
    }

    @Test
    void entriesAreImmutable() {
        var listing = new ConversationListing(null);
        assertTrue(listing.entries().isEmpty());
        assertTrue(ConversationListing.empty().isEmpty());
    }

    @Test
    void selectorMetadataRoundTrips() {
        var selector = new AttachmentSelector(REF_A, "4-9", "u3", "abc");
        var attachment = new Attachment("application/pdf", "a.pdf", 1, REF_A);
        attachment.getMetadata().putAll(selector.toMetadata());

        assertEquals(selector, AttachmentSelector.fromAttachment(attachment));
        assertTrue(AttachmentSelector.wholeFile(REF_A).toMetadata().isEmpty());
        assertEquals(AttachmentSelector.wholeFile(REF_A),
                AttachmentSelector.fromAttachment(new Attachment("application/pdf", "a.pdf", 1, REF_A)));
        assertNull(AttachmentSelector.fromAttachment(new Attachment("image/png", "x.png", 1, null)));
    }
}
