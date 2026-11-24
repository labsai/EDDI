/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.AttachmentShareService.Refusal;
import ai.labs.eddi.engine.documents.DocumentSettings;
import ai.labs.eddi.engine.documents.DocumentStructureService;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.modules.ingestion.extract.PdfFixtures;
import ai.labs.eddi.modules.ingestion.extract.PdfStructureExtractor;
import ai.labs.eddi.modules.ingestion.extract.document.DocumentUnit;
import ai.labs.eddi.modules.ingestion.extract.document.SplitConfig;
import ai.labs.eddi.modules.ingestion.extract.document.SplitStrategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sharing a part of a file: pages are authoritative, the grant is on the root,
 * and a refused part grants nothing. Real extractor, in-memory store, 24-hex
 * ids.
 */
class AttachmentSharePartsTest {

    private static final String A = "64b7f0c2a1d3e4f5a6b7c8d9";
    private static final String B = "64b7f0c2a1d3e4f5a6b7c8da";
    private static final String C = "64b7f0c2a1d3e4f5a6b7c8db";
    private static final String PDF = "6560a1b2c3d4e5f6a7b8c9d0";
    private static final String TXT = "6560a1b2c3d4e5f6a7b8c9d2";
    private static final String VIEW = "6560a1b2c3d4e5f6a7b8c9d3";

    /** Two-page windows: B's numbering of the same file. */
    private static final SplitConfig PAGES_OF_TWO = new SplitConfig(SplitStrategy.PAGES, 2, 12_000, 0, 60);

    private InMemoryAttachmentStore store;
    private DocumentStructureService structure;
    private AttachmentShareService service;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        store = spy(new InMemoryAttachmentStore().put(PDF, "report.pdf", "application/pdf", PdfFixtures.outlined(4, 3), A)
                .put(TXT, "notes.txt", "text/plain", new byte[]{'h', 'i'}, A));
        structure = new DocumentStructureService(store, new PdfStructureExtractor(), DocumentSettings.none());
        registry = new SimpleMeterRegistry();
        service = new AttachmentShareService(store, registry, null, structure);
    }

    private static SharePolicy policy(SplitConfig split) {
        return new SharePolicy(5, 10, 3, 0, Set.of(), null, split);
    }

    private ConversationListing listingOf(String conversation, List<Attachment> recorded) {
        return ConversationListing.of(conversation, store, Set.of(), recorded);
    }

    private ShareResult shareNames(String source, ConversationListing listing, String target, SharePolicy policy, String... names) {
        Resolution r = service.resolveNames(listing, List.of(names));
        assertTrue(r.refused().isEmpty(), r.refused().toString());
        return service.share(source, listing, r.selectors(), target, ShareReason.DELEGATION, policy, ShareParties.UNKNOWN);
    }

    private DocumentUnit unitOf(String id, SplitConfig split) {
        return structure.manifest(PDF, A, split).units().stream().filter(u -> u.id().equals(id)).findFirst().orElseThrow();
    }

    @Nested
    class Units {

        @Test
        void aUnit_isSharedAsItsPages_andGrantsTheRoot() {
            DocumentUnit u3 = unitOf("u3", SplitConfig.defaults());

            ShareResult result = shareNames(A, listingOf(A, List.of()), B, policy(SplitConfig.defaults()), "report.pdf#u3");

            assertEquals(1, result.shared().size(), result.refused().toString());
            AttachmentSelector shared = result.shared().get(0).selector();
            assertEquals(new AttachmentSelector(PDF, u3.firstPage() + "-" + u3.lastPage(), "u3", SplitConfig.defaults().hash()), shared);
            assertTrue(store.isGranted(PDF, B), "a part is a read grant on the whole file");
            assertTrue(result.shared().get(0).fileName().startsWith("report.pdf — u3, p."), result.shared().get(0).fileName());
        }

        @Test
        void theReceiverOfAChapter_passesOnTheSendersPages_underItsOwnSplit() throws Exception {
            DocumentUnit aU3 = unitOf("u3", SplitConfig.defaults());
            AttachmentSelector fromA = shareNames(A, listingOf(A, List.of()), B, policy(SplitConfig.defaults()), "report.pdf#u3").shared()
                    .get(0).selector();

            // B received A's selector on its turn and passes it on with its own split.
            Attachment atB = new Attachment("application/pdf", "report.pdf", 1, PDF);
            atB.getMetadata().putAll(fromA.toMetadata());
            ShareResult onward = service.share(B, listingOf(B, List.of()), List.of(AttachmentSelector.fromAttachment(atB)), C,
                    ShareReason.DELEGATION, policy(PAGES_OF_TWO), ShareParties.UNKNOWN);

            assertEquals(aU3.firstPage() + "-" + aU3.lastPage(), onward.shared().get(0).selector().pages(), "A's pages, not B's u3");
        }

        @Test
        void aUnitWithAForeignSplit_andNoPages_isRefused_withoutAGrant() throws Exception {
            var selector = new AttachmentSelector(PDF, null, "u3", SplitConfig.defaults().hash());

            ShareResult result = service.share(A, listingOf(A, List.of()), List.of(selector), B, ShareReason.DELEGATION, policy(PAGES_OF_TWO),
                    ShareParties.UNKNOWN);

            assertTrue(result.shared().isEmpty());
            assertTrue(result.refused().get(0).reason().contains("different split"), result.refused().get(0).reason());
            verify(store, never()).grantAccess(any(), any());
            assertEquals(1.0, registry.counter(AttachmentShareService.REFUSED_METER, "reason", Refusal.PART_INVALID.tag()).count());
        }

        @Test
        void anUnknownUnit_orPagesOutside_areRefused_withoutAGrant() throws Exception {
            ConversationListing listing = listingOf(A, List.of());
            Resolution r = service.resolveNames(listing, List.of("report.pdf#u40", "report.pdf#p11-13"));

            ShareResult result = service.share(A, listing, r.selectors(), B, ShareReason.DELEGATION, policy(null), ShareParties.UNKNOWN);

            assertTrue(result.shared().isEmpty());
            assertEquals(2, result.refused().size());
            verify(store, never()).grantAccess(any(), any());
        }

        @Test
        void aPartOfANonPdf_isRefused() throws Exception {
            ShareResult result = service.share(A, listingOf(A, List.of()), List.of(new AttachmentSelector(TXT, "1-1", null, null)), B,
                    ShareReason.DELEGATION, policy(null), ShareParties.UNKNOWN);

            assertTrue(result.shared().isEmpty());
            verify(store, never()).grantAccess(any(), any());
        }

        @Test
        void aPartOfAWithheldRoot_isNotFound() throws Exception {
            ConversationListing listing = ConversationListing.of(A, store, Set.of(PDF), List.of());

            ShareResult result = service.share(A, listing, List.of(new AttachmentSelector(PDF, "1-2", null, null)), B, ShareReason.DELEGATION,
                    policy(null), ShareParties.UNKNOWN);

            assertEquals(Refusal.NOT_FOUND.text(), result.refused().get(0).reason());
            verify(store, never()).grantAccess(any(), any());
        }

        @Test
        void aPartOfAFileTheSourceCannotRead_isNotFound() throws Exception {
            ShareResult result = service.share(C, listingOf(C, List.of()), List.of(new AttachmentSelector(PDF, "1-2", null, null)), B,
                    ShareReason.DELEGATION, policy(null), ShareParties.UNKNOWN);

            assertEquals(Refusal.NOT_FOUND.text(), result.refused().get(0).reason());
            verify(store, never()).grantAccess(any(), any());
        }

        @Test
        void aSplitHashAlone_neverWidensToTheWholeFile() throws Exception {
            var splitOnly = new AttachmentSelector(PDF, null, null, SplitConfig.defaults().hash());

            ShareResult result = service.share(A, listingOf(A, List.of()), List.of(splitOnly), B, ShareReason.DELEGATION, policy(null),
                    ShareParties.UNKNOWN);

            assertTrue(result.shared().isEmpty());
            verify(store, never()).grantAccess(any(), any());
        }

        @Test
        void aFaultWhileResolving_refusesThatFileOnly() throws Exception {
            var failing = mock(DocumentStructureService.class);
            when(failing.resolve(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("driver fault"));
            var withFailing = new AttachmentShareService(store, registry, null, failing);

            ShareResult result = withFailing.share(A, listingOf(A, List.of()),
                    List.of(AttachmentSelector.wholeFile(TXT), new AttachmentSelector(PDF, "1-2", null, null)), B, ShareReason.DELEGATION,
                    policy(null), ShareParties.UNKNOWN);

            assertEquals(1, result.shared().size(), "the file shared before the fault is still reported");
            assertEquals(Refusal.PART_INVALID.text(), result.refused().get(0).reason());
            verify(store, never()).grantAccess(PDF, B);
        }

        @Test
        void withoutDocumentStructure_partsAreRefused() throws Exception {
            var plain = new AttachmentShareService(store, null, null);

            assertEquals(Refusal.PART.text(), plain.resolveNames(listingOf(A, List.of()), List.of("report.pdf#u1")).refused().get(0).reason());
            ShareResult result = plain.share(A, listingOf(A, List.of()), List.of(new AttachmentSelector(PDF, "1-2", null, null)), B,
                    ShareReason.DELEGATION, policy(null), ShareParties.UNKNOWN);
            assertEquals(Refusal.PART.text(), result.refused().get(0).reason());
            verify(store, never()).grantAccess(any(), any());
        }
    }

    @Nested
    class Recorded {

        private Attachment recordedPart(String pages) {
            Attachment a = new Attachment("application/pdf", "report.pdf", 1, PDF);
            a.getMetadata().putAll(new AttachmentSelector(PDF, pages, "u2", SplitConfig.defaults().hash()).toMetadata());
            a.getMetadata().put(AttachmentSelector.META_DERIVED, "true");
            a.getMetadata().put(AttachmentSelector.META_ROOT, PDF);
            return a;
        }

        @Test
        void aRecordedPart_isNamedAndSharedAsRecorded() {
            ConversationListing listing = listingOf(A, List.of(recordedPart("4-6")));

            ShareResult result = shareNames(A, listing, B, policy(PAGES_OF_TWO), "report.pdf — u2, p.4–6");

            assertEquals("4-6", result.shared().get(0).selector().pages());
            assertTrue(store.isGranted(PDF, B));
        }

        @Test
        void aRecordedView_grantsItsRoot() throws Exception {
            store.putDerived(VIEW, "figure-1.png", "image/png", PDF, Derivation.Kind.IMAGE);
            Attachment view = new Attachment("image/png", "figure-1.png", 1, VIEW);
            view.getMetadata().put(AttachmentSelector.META_DERIVED, "true");
            view.getMetadata().put(AttachmentSelector.META_ROOT, PDF);

            ShareResult result = shareNames(A, listingOf(A, List.of(view)), B, policy(null), "figure-1.png");

            assertEquals(AttachmentSelector.wholeFile(VIEW), result.shared().get(0).selector());
            verify(store).grantAccess(PDF, B);
        }

        @Test
        void aPartSuffix_onARecordedEntry_isNotFound() {
            ConversationListing listing = listingOf(A, List.of(recordedPart("4-6")));

            var r = service.resolveNames(listing, List.of("report.pdf — u2, p.4–6#u1"));

            assertEquals(Refusal.NOT_FOUND.text(), r.refused().get(0).reason());
        }
    }
}
