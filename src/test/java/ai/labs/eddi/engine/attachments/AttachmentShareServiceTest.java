/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.AttachmentShareService.Refusal;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.modules.llm.tools.TurnCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AttachmentShareService}. Ids are realistic 24-hex
 * strings so that nothing is vacuously null.
 */
class AttachmentShareServiceTest {

    private static final String SOURCE = "64b7f0c2a1d3e4f5a6b7c8d9";
    private static final String TARGET = "64b7f0c2a1d3e4f5a6b7c8da";
    private static final String STRANGER = "64b7f0c2a1d3e4f5a6b7c8db";
    private static final String REF_PDF = "6560a1b2c3d4e5f6a7b8c9d0";
    private static final String REF_PNG = "6560a1b2c3d4e5f6a7b8c9d1";
    private static final String REF_TXT = "6560a1b2c3d4e5f6a7b8c9d2";
    private static final String REF_FOREIGN = "6560a1b2c3d4e5f6a7b8c9d3";

    private InMemoryAttachmentStore store;
    private SimpleMeterRegistry registry;
    private AuditLedgerService ledger;
    private AttachmentShareService service;
    private ConversationListing listing;

    @BeforeEach
    void setUp() {
        store = spy(new InMemoryAttachmentStore()
                .put(REF_PDF, "report.pdf", "application/pdf", 1200, SOURCE)
                .put(REF_PNG, "chart.png", "image/png", 300, SOURCE)
                .put(REF_TXT, "notes.txt", "text/plain", 40, SOURCE)
                .put(REF_FOREIGN, "secret.pdf", "application/pdf", 99, STRANGER));
        registry = new SimpleMeterRegistry();
        ledger = mock(AuditLedgerService.class);
        when(ledger.isEnabled()).thenReturn(true);
        service = new AttachmentShareService(store, registry, ledger);
        listing = ConversationListing.of(SOURCE, store, Set.of(), List.of());
    }

    private static SharePolicy policy(int files, int perTurn, int maxDepth, int depth) {
        return new SharePolicy(files, perTurn, maxDepth, depth, Set.of(), null);
    }

    private static SharePolicy open() {
        return policy(10, 10, 3, 0);
    }

    private double shared(String path) {
        var c = registry.find(AttachmentShareService.SHARED_METER).tag("path", path).counter();
        return c == null ? 0 : c.count();
    }

    private double refused(Refusal reason) {
        var c = registry.find(AttachmentShareService.REFUSED_METER).tag("reason", reason.tag()).counter();
        return c == null ? 0 : c.count();
    }

    private ShareResult share(List<AttachmentSelector> selectors, SharePolicy policy) {
        return service.share(SOURCE, listing, selectors, TARGET, ShareReason.DELEGATION, policy, ShareParties.UNKNOWN);
    }

    @Nested
    class ResolveNames {

        @Test
        void exactName() {
            var r = service.resolveNames(listing, List.of("chart.png"));
            assertEquals(List.of(AttachmentSelector.wholeFile(REF_PNG)), r.selectors());
            assertTrue(r.refused().isEmpty());
        }

        @Test
        void oneBasedIndex() {
            // Sorted by name: 1 = chart.png, 2 = notes.txt, 3 = report.pdf.
            var r = service.resolveNames(listing, List.of("1", "3"));
            assertEquals(List.of(AttachmentSelector.wholeFile(REF_PNG), AttachmentSelector.wholeFile(REF_PDF)), r.selectors());
        }

        @Test
        void indexOutOfRange_isNotFound() {
            var r = service.resolveNames(listing, List.of("0", "4", "-1", "99999999999"));
            assertTrue(r.resolved().isEmpty());
            assertEquals(4, r.refused().size());
            r.refused().forEach(x -> assertEquals(Refusal.NOT_FOUND.text(), x.reason()));
        }

        @Test
        void exactNameWinsOverIndex() {
            var s = new InMemoryAttachmentStore().put(REF_PDF, "a.pdf", "application/pdf", 1, SOURCE).put(REF_PNG, "1",
                    "image/png", 1, SOURCE);
            var l = ConversationListing.of(SOURCE, s, Set.of(), List.of());

            assertEquals(List.of(AttachmentSelector.wholeFile(REF_PNG)), service.resolveNames(l, List.of("1")).selectors());
        }

        @Test
        void foreignName_isNotFound_sameAsNonexistent() {
            var r = service.resolveNames(listing, List.of("secret.pdf", "nothing.pdf"));
            assertTrue(r.resolved().isEmpty());
            assertEquals(r.refused().get(0).reason(), r.refused().get(1).reason());
            assertEquals(Refusal.NOT_FOUND.text(), r.refused().get(0).reason());
            assertEquals(2, refused(Refusal.NOT_FOUND));
        }

        @Test
        void storageRef_isNotAName() {
            var r = service.resolveNames(listing, List.of(REF_PDF));
            assertTrue(r.resolved().isEmpty());
        }

        @Test
        void ambiguousName_isRefusedWithNumbers_noReferences() {
            var s = new InMemoryAttachmentStore().put(REF_PDF, "report.pdf", "application/pdf", 1, SOURCE).put(REF_PNG, "x.png",
                    "image/png", 1, SOURCE).put(REF_TXT, "report.pdf", "application/pdf", 2, SOURCE);
            var l = ConversationListing.of(SOURCE, s, Set.of(), List.of());

            var r = service.resolveNames(l, List.of("report.pdf"));

            assertTrue(r.resolved().isEmpty());
            String reason = r.refused().get(0).reason();
            assertTrue(reason.startsWith("ambiguous"), reason);
            assertTrue(reason.contains("1, 2"), reason);
            assertFalse(reason.contains(REF_PDF) || reason.contains(REF_TXT), reason);
            assertEquals(1, refused(Refusal.AMBIGUOUS));
        }

        @Test
        void partSuffix_isRefused() {
            var r = service.resolveNames(listing, List.of("report.pdf#u3", "report.pdf#p1-2"));
            assertTrue(r.resolved().isEmpty());
            r.refused().forEach(x -> assertEquals(Refusal.PART.text(), x.reason()));
            assertTrue(Refusal.PART.text().startsWith("parts of a file cannot be shared yet"));
        }

        @Test
        void longRequestedName_isEchoedCut() {
            var r = service.resolveNames(listing, List.of("x".repeat(5000)));

            String echoed = r.refused().get(0).requestedName();
            assertEquals(AttachmentShareService.MAX_ECHO_CHARS + 1, echoed.length());
            assertTrue(echoed.endsWith("…"));
            var part = service.resolveNames(listing, List.of("y".repeat(5000) + "#u3"));
            assertTrue(part.refused().get(0).requestedName().length() <= AttachmentShareService.MAX_ECHO_CHARS + 1);
        }

        @Test
        void sameFileTwice_resolvesOnce() {
            var r = service.resolveNames(listing, List.of("report.pdf", "3"));
            assertEquals(1, r.resolved().size());
        }

        @Test
        void nullsAndBlanks() {
            assertTrue(service.resolveNames(null, List.of("x")).resolved().isEmpty());
            assertTrue(service.resolveNames(listing, null).refused().isEmpty());
            var r = service.resolveNames(listing, Arrays.asList(null, " "));
            assertEquals(2, r.refused().size());
        }
    }

    @Nested
    class Share {

        @Test
        void sharesTheRoot_meters_andAudits() throws Exception {
            var result = share(List.of(AttachmentSelector.wholeFile(REF_PDF)), open());

            assertEquals(1, result.shared().size());
            var s = result.shared().get(0);
            assertEquals(new ShareResult.Shared(AttachmentSelector.wholeFile(REF_PDF), "report.pdf", "application/pdf", 1200), s);
            verify(store).grantAccess(REF_PDF, TARGET);
            assertTrue(store.isGranted(REF_PDF, TARGET));
            assertEquals(1, shared("delegation"));

            var captor = ArgumentCaptor.forClass(AuditEntry.class);
            verify(ledger).submit(captor.capture());
            AuditEntry entry = captor.getValue();
            assertEquals(SOURCE, entry.conversationId());
            assertEquals(AttachmentShareService.AUDIT_TASK_ID, entry.taskId());
            assertEquals("DELEGATION", entry.output().get("reason"));
            assertEquals(TARGET, entry.output().get("targetConversationId"));
            assertEquals("application/pdf", entry.output().get("mimeType"));
            assertEquals(1200L, entry.output().get("sizeBytes"));
            assertFalse(entry.output().containsValue("report.pdf"), "no file name in the audit entry");
            assertFalse(entry.output().containsKey("storageRef"), "no capability in the audit entry");
            assertFalse(entry.output().containsValue(REF_PDF), "no capability in the audit entry");
        }

        @Test
        void repeatedSelector_isSharedOnce() throws Exception {
            var counter = TurnCounter.local();

            var result = share(List.of(AttachmentSelector.wholeFile(REF_PDF), AttachmentSelector.wholeFile(REF_PDF),
                    AttachmentSelector.wholeFile(REF_PNG)), new SharePolicy(2, 10, 3, 0, Set.of(), counter));

            assertEquals(List.of("report.pdf", "chart.png"), result.shared().stream().map(ShareResult.Shared::fileName).toList());
            assertTrue(result.refused().isEmpty());
            assertEquals(2, counter.get());
            verify(store).grantAccess(REF_PDF, TARGET);
            verify(ledger, times(2)).submit(any());
        }

        @Test
        void callRefusal_readsNoStore_andNamesNoFile() {
            var mockStore = mock(IAttachmentStore.class);
            var s = new AttachmentShareService(mockStore, registry, ledger);

            var result = s.share(SOURCE, listing, List.of(AttachmentSelector.wholeFile(REF_PDF)), SOURCE, ShareReason.DELEGATION, open(),
                    ShareParties.UNKNOWN);

            assertEquals(new ShareResult.Refused(AttachmentShareService.UNKNOWN_FILE, Refusal.TARGET.text()), result.refused().get(0));
            verifyNoInteractions(mockStore);
        }

        @Test
        void nullMimeEntry_allowsNothing_ratherThanEverything() {
            var allowed = new HashSet<String>();
            allowed.add(null);
            var policy = new SharePolicy(10, 10, 3, 0, allowed, null);

            assertFalse(policy.allowsMimeType("application/pdf"));
            assertEquals(Refusal.MIME_TYPE.text(), share(List.of(AttachmentSelector.wholeFile(REF_PDF)), policy).refused().get(0).reason());
            assertTrue(new SharePolicy(10, 10, 3, 0, Set.of(" Application/PDF "), null).allowsMimeType("application/pdf"));
        }

        @Test
        void auditCarriesTheParties() {
            service.share(SOURCE, listing, List.of(AttachmentSelector.wholeFile(REF_PDF)), TARGET, ShareReason.DELEGATION, open(),
                    new ShareParties("user-1", "agent-a", 4, "agent-b", "production"));

            var captor = ArgumentCaptor.forClass(AuditEntry.class);
            verify(ledger).submit(captor.capture());
            assertEquals("user-1", captor.getValue().userId());
            assertEquals("agent-a", captor.getValue().agentId());
            assertEquals(4, captor.getValue().agentVersion());
            assertEquals("production", captor.getValue().environment());
            assertEquals("agent-b", captor.getValue().output().get("targetAgentId"));
        }

        @Test
        void disabledLedger_noEntry_failingLedger_noThrow() {
            when(ledger.isEnabled()).thenReturn(false);
            assertEquals(1, share(List.of(AttachmentSelector.wholeFile(REF_PDF)), open()).shared().size());
            verify(ledger, never()).submit(any());

            when(ledger.isEnabled()).thenReturn(true);
            when(ledger.submit(any())).thenThrow(new IllegalStateException("ledger down"));
            assertEquals(1, share(List.of(AttachmentSelector.wholeFile(REF_PNG)), open()).shared().size());
        }

        @Test
        void selectorNotFromTheListing_isRefused_noGrant() throws Exception {
            var result = share(List.of(AttachmentSelector.wholeFile(REF_FOREIGN)), open());

            assertTrue(result.shared().isEmpty());
            assertEquals(Refusal.NOT_FOUND.text(), result.refused().get(0).reason());
            assertFalse(result.refused().get(0).requestedName().contains(REF_FOREIGN));
            verify(store, never()).grantAccess(any(), any());
            verify(ledger, never()).submit(any());
        }

        @Test
        void readableFileMissingFromTheGivenListing_isRefused() throws Exception {
            var result = service.share(SOURCE, new ConversationListing(SOURCE, List.of(), Set.of()),
                    List.of(AttachmentSelector.wholeFile(REF_PDF)), TARGET,
                    ShareReason.DELEGATION, open(), ShareParties.UNKNOWN);

            assertTrue(result.shared().isEmpty());
            verify(store, never()).grantAccess(any(), any());
        }

        @Test
        void emptyListing_refusesEverything() throws Exception {
            var result = service.share(SOURCE, ConversationListing.empty(), List.of(AttachmentSelector.wholeFile(REF_PDF)), TARGET,
                    ShareReason.DELEGATION, open(), ShareParties.UNKNOWN);

            assertEquals(new ShareResult.Refused(AttachmentShareService.UNKNOWN_FILE, Refusal.NOT_FOUND.text()), result.refused().get(0));
            verify(store, never()).grantAccess(any(), any());
        }

        @Test
        void staleListingOfThisSource_isRefusedByTheReadCheck() throws Exception {
            // Built for SOURCE, but holding an entry SOURCE cannot read (stale, or built by
            // hand).
            var strangers = ConversationListing.of(STRANGER, store, Set.of(), List.of());
            var stale = new ConversationListing(SOURCE, strangers.entries(), Set.of());

            var result = service.share(SOURCE, stale, List.of(AttachmentSelector.wholeFile(REF_FOREIGN)), TARGET, ShareReason.DELEGATION,
                    open(), ShareParties.UNKNOWN);

            assertEquals(new ShareResult.Refused(AttachmentShareService.UNKNOWN_FILE, Refusal.NOT_FOUND.text()), result.refused().get(0));
            verify(store, never()).grantAccess(any(), any());
        }

        @Test
        void handBuiltListingWithAWithheldRoot_isRefused() throws Exception {
            var full = ConversationListing.of(SOURCE, store, Set.of(), List.of());
            var withheld = new ConversationListing(SOURCE, full.entries(), Set.of(REF_PDF));

            var result = service.share(SOURCE, withheld, List.of(AttachmentSelector.wholeFile(REF_PDF)), TARGET, ShareReason.DELEGATION,
                    open(), ShareParties.UNKNOWN);

            assertTrue(result.shared().isEmpty());
            assertEquals(new ShareResult.Refused(AttachmentShareService.UNKNOWN_FILE, Refusal.NOT_FOUND.text()), result.refused().get(0));
            verify(store, never()).grantAccess(any(), any());
        }

        @Test
        void listingOfAnotherConversation_isRefused() throws Exception {
            var strangers = ConversationListing.of(STRANGER, store, Set.of(), List.of());

            var result = service.share(SOURCE, strangers, List.of(AttachmentSelector.wholeFile(REF_FOREIGN)), TARGET,
                    ShareReason.DELEGATION, open(), ShareParties.UNKNOWN);

            assertTrue(result.shared().isEmpty());
            assertEquals(Refusal.NOT_FOUND.text(), result.refused().get(0).reason());
            assertEquals(AttachmentShareService.UNKNOWN_FILE, result.refused().get(0).requestedName(),
                    "a file the source cannot read keeps its name");
            verify(store, never()).grantAccess(any(), any());
        }

        @Test
        void partSelector_isRefused() throws Exception {
            var result = share(List.of(new AttachmentSelector(REF_PDF, "1-3", "u1", "abc")), open());

            assertEquals(Refusal.PART.text(), result.refused().get(0).reason());
            assertEquals("report.pdf", result.refused().get(0).requestedName());
            verify(store, never()).grantAccess(any(), any());
        }

        @Test
        void mimeTypeNotAllowed_isRefused() throws Exception {
            var policy = new SharePolicy(10, 10, 3, 0, Set.of("Application/PDF"), null);

            var result = share(List.of(AttachmentSelector.wholeFile(REF_PNG), AttachmentSelector.wholeFile(REF_PDF)), policy);

            assertEquals(List.of("report.pdf"), result.shared().stream().map(ShareResult.Shared::fileName).toList());
            assertEquals(Refusal.MIME_TYPE.text(), result.refused().get(0).reason());
            verify(store, never()).grantAccess(REF_PNG, TARGET);
        }

        @Test
        void depthAtLimit_isRefused() throws Exception {
            var result = share(List.of(AttachmentSelector.wholeFile(REF_PDF)), policy(10, 10, 3, 3));

            assertEquals(Refusal.DEPTH.text(), result.refused().get(0).reason());
            verify(store, never()).grantAccess(any(), any());
            assertEquals(1, refused(Refusal.DEPTH));
        }

        @Test
        void depthBelowLimit_isShared() {
            assertEquals(1, share(List.of(AttachmentSelector.wholeFile(REF_PDF)), policy(10, 10, 3, 2)).shared().size());
        }

        @Test
        void filesPerShareCap() throws Exception {
            var result = share(List.of(AttachmentSelector.wholeFile(REF_PDF), AttachmentSelector.wholeFile(REF_PNG),
                    AttachmentSelector.wholeFile(REF_TXT)), policy(2, 10, 3, 0));

            assertEquals(2, result.shared().size());
            assertEquals(Refusal.FILES_PER_SHARE.text(), result.refused().get(0).reason());
            verify(store, never()).grantAccess(REF_TXT, TARGET);
        }

        @Test
        void perTurnCap_spansCalls() throws Exception {
            var counter = TurnCounter.local();
            var policy = new SharePolicy(10, 2, 3, 0, Set.of(), counter);

            assertEquals(2, share(List.of(AttachmentSelector.wholeFile(REF_PDF), AttachmentSelector.wholeFile(REF_PNG)), policy)
                    .shared().size());
            var second = share(List.of(AttachmentSelector.wholeFile(REF_TXT)), policy);

            assertTrue(second.shared().isEmpty());
            assertEquals(Refusal.SHARES_PER_TURN.text(), second.refused().get(0).reason());
            verify(store, never()).grantAccess(REF_TXT, TARGET);
            assertEquals(2, counter.get());
        }

        @Test
        void refusals_burnNoSlot() {
            var counter = TurnCounter.local();
            var policy = new SharePolicy(1, 1, 3, 0, Set.of(), counter);

            var result = share(List.of(AttachmentSelector.wholeFile(REF_FOREIGN), new AttachmentSelector(REF_PDF, "1-2", null, null),
                    AttachmentSelector.wholeFile(REF_PNG)), policy);

            assertEquals(List.of("chart.png"), result.shared().stream().map(ShareResult.Shared::fileName).toList());
            assertEquals(2, result.refused().size());
            assertEquals(1, counter.get());
        }

        @Test
        void failedGrant_isRefused_andBurnsNoSlot() throws Exception {
            doThrow(new IAttachmentStore.AttachmentStoreException("db down")).when(store).grantAccess(REF_PDF, TARGET);
            var counter = TurnCounter.local();

            var result = share(List.of(AttachmentSelector.wholeFile(REF_PDF), AttachmentSelector.wholeFile(REF_PNG)),
                    new SharePolicy(1, 1, 3, 0, Set.of(), counter));

            assertEquals(Refusal.GRANT_FAILED.text(), result.refused().get(0).reason());
            assertEquals(List.of("chart.png"), result.shared().stream().map(ShareResult.Shared::fileName).toList());
            assertEquals(1, counter.get());
        }

        @Test
        void targetMustBeAnotherConversation() throws Exception {
            for (String target : Arrays.asList(null, "", SOURCE)) {
                var result = service.share(SOURCE, listing, List.of(AttachmentSelector.wholeFile(REF_PDF)), target,
                        ShareReason.DELEGATION, open(), ShareParties.UNKNOWN);
                assertEquals(Refusal.TARGET.text(), result.refused().get(0).reason());
            }
            verify(store, never()).grantAccess(any(), any());
        }

        @Test
        void noStore_refusesEverything_neverThrows() {
            var none = new AttachmentShareService((IAttachmentStore) null, registry, ledger);

            var result = none.share(SOURCE, listing, List.of(AttachmentSelector.wholeFile(REF_PDF)), TARGET, ShareReason.DELEGATION,
                    open(), ShareParties.UNKNOWN);

            assertEquals(Refusal.UNAVAILABLE.text(), result.refused().get(0).reason());
            assertFalse(none.mayRead(REF_PDF, SOURCE));
        }

        @Test
        void unresolvableStoreInstance_refuses() {
            @SuppressWarnings("unchecked")
            Instance<IAttachmentStore> instance = mock(Instance.class);
            when(instance.isResolvable()).thenReturn(false);
            var cdi = new AttachmentShareService(instance, registry, ledger);

            assertFalse(cdi.mayRead(REF_PDF, SOURCE));
            assertEquals(Refusal.UNAVAILABLE.text(), cdi.share(SOURCE, listing, List.of(AttachmentSelector.wholeFile(REF_PDF)),
                    TARGET, ShareReason.DELEGATION, open(), ShareParties.UNKNOWN).refused().get(0).reason());
        }

        @Test
        void emptyRequest_isEmptyResult() {
            assertTrue(share(List.of(), open()).shared().isEmpty());
            assertTrue(share(null, open()).refused().isEmpty());
        }

        @Test
        void stepRecords_carryNoReferences() {
            var result = share(List.of(AttachmentSelector.wholeFile(REF_PDF)), open());

            List<Map<String, String>> rows = result.toStepRecords("agent-b", ShareReason.DELEGATION);

            assertEquals(List.of(Map.of("fileName", "report.pdf", "targetAgentId", "agent-b", "reason", "DELEGATION")), rows);
        }
    }

    @Nested
    class MayRead {

        @Test
        void ownerAndGrantee_mayRead_strangerMayNot() throws Exception {
            assertTrue(service.mayRead(REF_PDF, SOURCE));
            assertFalse(service.mayRead(REF_PDF, TARGET));
            store.grantAccess(REF_PDF, TARGET);
            assertTrue(service.mayRead(REF_PDF, TARGET));
            assertFalse(service.mayRead(REF_FOREIGN, SOURCE));
        }

        @Test
        void blankArguments_areFalse_withoutAStoreCall() {
            var mockStore = mock(IAttachmentStore.class);
            var s = new AttachmentShareService(mockStore, null, null);
            assertFalse(s.mayRead(null, SOURCE));
            assertFalse(s.mayRead(" ", SOURCE));
            assertFalse(s.mayRead(REF_PDF, null));
            assertFalse(s.mayRead(REF_PDF, ""));
            verifyNoInteractions(mockStore);
        }

        @Test
        void unknownBlob_isFalse() {
            assertFalse(service.mayRead("6560a1b2c3d4e5f6a7b8c9ff", SOURCE));
        }
    }
}
