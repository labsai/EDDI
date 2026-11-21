/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import ai.labs.eddi.engine.attachments.AttachmentOwnerChanged;
import ai.labs.eddi.engine.attachments.AttachmentRootDeleted;
import ai.labs.eddi.engine.attachments.AttachmentSelector;
import ai.labs.eddi.engine.attachments.Derivation;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.InMemoryAttachmentStore;
import ai.labs.eddi.engine.attachments.OwnerScope;
import ai.labs.eddi.engine.documents.SelectorException.Reason;
import ai.labs.eddi.modules.ingestion.extract.PdfFixtures;
import ai.labs.eddi.modules.ingestion.extract.PdfStructureExtractor;
import ai.labs.eddi.modules.ingestion.extract.document.DocumentManifest;
import ai.labs.eddi.modules.ingestion.extract.document.DocumentUnit;
import ai.labs.eddi.modules.ingestion.extract.document.SplitConfig;
import ai.labs.eddi.modules.ingestion.extract.document.SplitStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link DocumentStructureService} over an in-memory store with the real
 * stores' ownership rules and the real extractor. Ids are 24-hex.
 */
class DocumentStructureServiceTest {

    private static final String OWNER = "64b7f0c2a1d3e4f5a6b7c8d9";
    private static final String GRANTEE = "64b7f0c2a1d3e4f5a6b7c8da";
    private static final String STRANGER = "64b7f0c2a1d3e4f5a6b7c8db";
    private static final String OTHER_OWNER = "64b7f0c2a1d3e4f5a6b7c8dc";
    private static final String PDF = "6560a1b2c3d4e5f6a7b8c9d0";
    private static final String PDF_COPY = "6560a1b2c3d4e5f6a7b8c9d1";
    private static final String TXT = "6560a1b2c3d4e5f6a7b8c9d2";
    private static final String VIEW = "6560a1b2c3d4e5f6a7b8c9d3";

    /** Four outline chapters of three pages each. */
    private static final byte[] OUTLINED = PdfFixtures.outlined(4, 3);

    /** Two-page windows: a different numbering of the same file. */
    private static final SplitConfig PAGES_OF_TWO = new SplitConfig(SplitStrategy.PAGES, 2, 12_000, 0, 60);

    private InMemoryAttachmentStore store;
    private PdfStructureExtractor extractor;
    private DocumentStructureService service;

    @BeforeEach
    void setUp() throws Exception {
        store = spy(new InMemoryAttachmentStore().put(PDF, "report.pdf", "application/pdf", OUTLINED, OWNER)
                .put(PDF_COPY, "report.pdf", "application/pdf", OUTLINED, OTHER_OWNER)
                .put(TXT, "notes.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8), OWNER));
        store.grantAccess(PDF, GRANTEE);
        extractor = spy(new PdfStructureExtractor());
        service = new DocumentStructureService(store, extractor, DocumentSettings.none());
    }

    private DocumentStructureService freshService() {
        return new DocumentStructureService(store, extractor, DocumentSettings.none());
    }

    private static DocumentUnit unit(DocumentManifest m, String id) {
        return m.units().stream().filter(u -> u.id().equals(id)).findFirst().orElseThrow();
    }

    @Nested
    class Manifest {

        @Test
        void isExtracted_andStoredAsTheRootsManifest() throws Exception {
            DocumentManifest m = service.manifest(PDF, OWNER, SplitConfig.defaults());

            assertEquals(PDF, m.rootRef());
            assertEquals(12, m.pageCount());
            List<String> stored = store.derivedRefs(PDF, Derivation.Kind.MANIFEST);
            assertEquals(1, stored.size());
            Derivation d = store.getDerivation(stored.get(0), OWNER).orElseThrow();
            assertEquals(DocumentStructureService.MANIFEST_UNIT_PREFIX + SplitConfig.defaults().hash(), d.unitId());
            assertEquals(12, store.pageCount(PDF, OWNER), "the page count is recorded on the root");
        }

        @Test
        void storedManifest_holdsNoStorageReference() throws Exception {
            service.manifest(PDF, OWNER, SplitConfig.defaults());
            String ref = store.derivedRefs(PDF, Derivation.Kind.MANIFEST).get(0);

            String json = new String(store.load(ref, OWNER), StandardCharsets.UTF_8);

            assertFalse(json.contains(PDF), json);
            assertTrue(json.contains(SplitConfig.defaults().hash()), "the split is part of the payload");
        }

        @Test
        void grantee_causesAManifestOwnedByTheRootsOwner() throws Exception {
            service.manifest(PDF, GRANTEE, SplitConfig.defaults());

            String ref = store.derivedRefs(PDF, Derivation.Kind.MANIFEST).get(0);
            verify(store).storeDerived(any(), eq("application/json"), anyString(), any(), eq(GRANTEE));
            assertEquals(OWNER, store.getMetadata(ref, OWNER).conversationId());
            assertEquals(OWNER, store.ownerOf(ref, GRANTEE).ownerKey());
        }

        @Test
        void secondCall_isServedFromTheCache() throws Exception {
            service.manifest(PDF, OWNER, SplitConfig.defaults());
            service.manifest(PDF, GRANTEE, SplitConfig.defaults());

            verify(store, times(1)).load(PDF, OWNER);
            verify(store, never()).load(eq(PDF), eq(GRANTEE));
            verify(extractor, times(1)).manifest(any(), any(), any(), any());
        }

        @Test
        void coldCache_readsTheStoredManifest_withoutExtracting() throws Exception {
            DocumentManifest first = service.manifest(PDF, OWNER, SplitConfig.defaults());

            DocumentManifest second = freshService().manifest(PDF, OWNER, SplitConfig.defaults());

            assertEquals(first, second);
            verify(extractor, times(1)).manifest(any(), any(), any(), any());
            verify(store, times(1)).load(PDF, OWNER);
        }

        @Test
        void stranger_isNotFound_evenWithAWarmCache() throws Exception {
            service.manifest(PDF, OWNER, SplitConfig.defaults());

            var e = assertThrows(SelectorException.class, () -> service.manifest(PDF, STRANGER, SplitConfig.defaults()));

            assertEquals(Reason.NOT_FOUND, e.reason());
            assertEquals("not found", e.getMessage());
        }

        @Test
        void twoOwnersOfTheSameBytes_shareNothing() throws Exception {
            service.manifest(PDF, OWNER, SplitConfig.defaults());
            service.manifest(PDF_COPY, OTHER_OWNER, SplitConfig.defaults());

            verify(extractor, times(2)).manifest(any(), any(), any(), any());
            assertEquals(1, store.derivedRefs(PDF, Derivation.Kind.MANIFEST).size());
            assertEquals(1, store.derivedRefs(PDF_COPY, Derivation.Kind.MANIFEST).size());
        }

        @Test
        void twoSplitsThatCutAlike_storeTwoFindableManifests() throws Exception {
            // Different configurations, same units: only the split hash tells them apart.
            SplitConfig a = new SplitConfig(SplitStrategy.PAGES, 20, 12_000, 0, 60);
            SplitConfig b = new SplitConfig(SplitStrategy.PAGES, 30, 12_000, 0, 60);
            assertEquals(service.manifest(PDF, OWNER, a).units(), service.manifest(PDF, OWNER, b).units());

            assertEquals(2, store.derivedRefs(PDF, Derivation.Kind.MANIFEST).size());
            freshService().manifest(PDF, OWNER, b);
            verify(extractor, times(2)).manifest(any(), any(), any(), any());
        }

        @Test
        void aStoreRefusal_stillAnswersTheCall() throws Exception {
            doThrow(new IAttachmentStore.AttachmentDerivedCapException("max-text-bytes-per-source", "cap")).when(store)
                    .storeDerived(any(), any(), any(), any(), any());

            assertEquals(12, service.manifest(PDF, OWNER, SplitConfig.defaults()).pageCount());
        }

        @Test
        void notAPdf_isRefused() {
            assertEquals(Reason.NOT_PDF, assertThrows(SelectorException.class, () -> service.manifest(TXT, OWNER, null)).reason());
        }

        @Test
        void unreadablePdf_isReported() {
            store.put(VIEW, "broken.pdf", "application/pdf", "%PDF-1.7 junk".getBytes(StandardCharsets.UTF_8), OWNER);

            assertEquals(Reason.UNREADABLE, assertThrows(SelectorException.class, () -> service.manifest(VIEW, OWNER, null)).reason());
        }

        @Test
        void withoutAStore_isNotFound() {
            var none = new DocumentStructureService((IAttachmentStore) null, extractor, null);
            assertEquals(Reason.NOT_FOUND, assertThrows(SelectorException.class, () -> none.manifest(PDF, OWNER, null)).reason());
        }

        @Test
        void rootDeletedOrOwnerChanged_evictsTheCache() {
            service.manifest(PDF, OWNER, SplitConfig.defaults());
            service.manifest(PDF_COPY, OTHER_OWNER, SplitConfig.defaults());
            assertEquals(2, service.cachedCount());

            service.onRootDeleted(new AttachmentRootDeleted(PDF, OwnerScope.CONVERSATION, OWNER, null));
            assertEquals(1, service.cachedCount());
            service.onOwnerChanged(new AttachmentOwnerChanged(PDF_COPY, OTHER_OWNER, "user:x", OwnerScope.USER));
            assertEquals(0, service.cachedCount());
        }

        @Test
        void evictOwner_dropsOnlyThatOwner() {
            service.manifest(PDF, OWNER, SplitConfig.defaults());
            service.manifest(PDF_COPY, OTHER_OWNER, SplitConfig.defaults());

            service.evictOwner(OWNER);

            assertEquals(1, service.cachedCount());
        }
    }

    @Nested
    class Resolve {

        @Test
        void unit_becomesPages_withUnitAndSplit() {
            DocumentManifest m = service.manifest(PDF, OWNER, SplitConfig.defaults());
            DocumentUnit u3 = unit(m, "u3");

            AttachmentSelector s = service.resolve(PDF, OWNER, "u3", null, SplitConfig.defaults());

            assertEquals(new AttachmentSelector(PDF, u3.firstPage() + "-" + u3.lastPage(), "u3", SplitConfig.defaults().hash()), s);
        }

        @Test
        void aChapterKeepsTheSendersPages_underAnotherSplit() {
            // A resolves u3 under its split; B, whose split numbers the file in two-page
            // windows, receives A's selector and must render A's pages.
            AttachmentSelector fromA = service.resolve(PDF, OWNER, "u3", null, SplitConfig.defaults());
            AttachmentSelector bOwnU3 = service.resolve(PDF, GRANTEE, "u3", null, PAGES_OF_TWO);
            assertNotEquals(fromA.pages(), bOwnU3.pages(), "the fixture must number the chapters differently");

            AttachmentSelector atB = service.resolve(PDF, GRANTEE, fromA.unit(), fromA.pages(), fromA.split(), PAGES_OF_TWO);

            assertEquals(fromA, atB);
        }

        @Test
        void unitWithAForeignSplit_andNoPages_isRefused() {
            String foreign = SplitConfig.defaults().hash();

            var e = assertThrows(SelectorException.class, () -> service.resolve(PDF, GRANTEE, "u3", null, foreign, PAGES_OF_TWO));

            assertEquals(Reason.FOREIGN_SPLIT, e.reason());
        }

        @Test
        void unknownUnit_andPagesOutside_areRefused() {
            assertEquals(Reason.UNKNOWN_UNIT,
                    assertThrows(SelectorException.class, () -> service.resolve(PDF, OWNER, "u40", null, null)).reason());
            assertEquals(Reason.PAGES_OUT_OF_RANGE,
                    assertThrows(SelectorException.class, () -> service.resolve(PDF, OWNER, null, "11-13", null)).reason());
        }

        @Test
        void pages_useTheRecordedPageCount_withoutExtracting() throws Exception {
            store.setPageCount(PDF, 12);

            assertEquals("2-4", service.resolve(PDF, OWNER, null, "2-4", null).pages());

            verify(extractor, never()).manifest(any(), any(), any(), any());
        }

        @Test
        void wholeFile_needsNoStore() throws Exception {
            assertTrue(service.resolve(PDF, STRANGER, null, null, null).isWholeFile());
            verify(store, never()).ownerOf(anyString(), anyString());
        }

        @Test
        void stranger_cannotResolveAPart() {
            assertEquals(Reason.NOT_FOUND,
                    assertThrows(SelectorException.class, () -> service.resolve(PDF, STRANGER, null, "1-2", null)).reason());
        }

        @Test
        void aPartOfANonPdf_orOfAView_isRefused() {
            store.putDerived(VIEW, "p1.png", "image/png", PDF, Derivation.Kind.IMAGE);

            assertEquals(Reason.NOT_PDF, assertThrows(SelectorException.class, () -> service.resolve(TXT, OWNER, null, "1-1", null)).reason());
            assertEquals(Reason.NOT_PDF, assertThrows(SelectorException.class, () -> service.resolve(VIEW, OWNER, null, "1-1", null)).reason());
        }

        @Test
        void partResolver_appliesTheGivenSplit() {
            var resolver = service.partResolver(PAGES_OF_TWO);

            AttachmentSelector s = resolver.resolve(PDF, OWNER, "u2", null, null);

            assertEquals(new AttachmentSelector(PDF, "3-4", "u2", PAGES_OF_TWO.hash()), s);
        }
    }

    @Test
    void deploymentSplit_isTheBuiltInDefaultWhileNoSplitKeyIsLive() {
        var settings = DocumentSettings.of(Map.of("split.pagesPerUnit", "3"), DocumentBounds.none());
        var s = new DocumentStructureService(store, extractor, settings);

        assertEquals(SplitConfig.defaults(), s.deploymentSplit());
    }
}
