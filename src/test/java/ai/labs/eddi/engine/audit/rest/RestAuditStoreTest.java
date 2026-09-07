/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit.rest;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.AuditRecoveryBudget;
import ai.labs.eddi.engine.audit.AuditVerificationStatus;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.audit.model.AuditVerificationReport.ChainStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestAuditStore} — verifies correct delegation to
 * {@link IAuditStore}.
 */
class RestAuditStoreTest {

    private IAuditStore auditStore;
    private AuditLedgerService auditLedgerService;
    private RestAuditStore restAuditStore;

    private AuditEntry sampleEntry() {
        return new AuditEntry("id-1", "conv-1", "agent-1", 1, "user-1", "production", 0, "task-1", "test-type", 0, 42L, Map.of("userInput", "hello"),
                Map.of("output", List.of("world")), null, null, List.of("greet"), 0.0, Instant.now(), "hmac-abc", null);
    }

    @BeforeEach
    void setUp() {
        auditStore = mock(IAuditStore.class);
        auditLedgerService = mock(AuditLedgerService.class);
        when(auditLedgerService.isSigningEnabled()).thenReturn(true);
        // The sweep asks the service for one budget and passes it to every entry.
        when(auditLedgerService.newRecoveryBudget()).thenReturn(AuditRecoveryBudget.none());
        when(auditLedgerService.verifyEntry(any(), any())).thenReturn(AuditVerificationStatus.VALID);
        restAuditStore = new RestAuditStore(auditStore, auditLedgerService);
    }

    @Test
    @DisplayName("getAuditTrail delegates to auditStore.getEntries")
    void getAuditTrail_delegatesToStore() {
        var expected = List.of(sampleEntry());
        when(auditStore.getEntries("conv-1", 5, 50)).thenReturn(expected);

        var result = restAuditStore.getAuditTrail("conv-1", 5, 50);

        assertEquals(expected, result);
        verify(auditStore).getEntries("conv-1", 5, 50);
    }

    @Test
    @DisplayName("getAuditTrailByAgent delegates to auditStore.getEntriesByAgent")
    void getAuditTrailByAgent_delegatesToStore() {
        var expected = List.of(sampleEntry());
        when(auditStore.getEntriesByAgent("agent-1", 1, 0, 100)).thenReturn(expected);

        var result = restAuditStore.getAuditTrailByAgent("agent-1", 1, 0, 100);

        assertEquals(expected, result);
        verify(auditStore).getEntriesByAgent("agent-1", 1, 0, 100);
    }

    /**
     * Finding 08. The read endpoints used to pass the query parameter through
     * untouched, and the two backends disagree about what an out-of-range value
     * means: MongoDB reads {@code limit <= 0} as "no limit" and materialises every
     * audit entry ever written for the scope — millions of rows carrying full
     * prompts — into one response on the request thread, while PostgreSQL answers a
     * 500 for a negative {@code LIMIT} or {@code OFFSET}. The verification
     * endpoints in the same class already clamped for exactly this reason.
     */
    @Test
    @DisplayName("getAuditTrail clamps an unusable limit instead of returning the whole collection")
    void getAuditTrail_clampsSkipAndLimit() {
        restAuditStore.getAuditTrail("conv-1", -5, 0);
        verify(auditStore).getEntries("conv-1", 0, RestAuditStore.DEFAULT_READ_LIMIT);

        restAuditStore.getAuditTrail("conv-1", 0, 10_000);
        verify(auditStore).getEntries("conv-1", 0, RestAuditStore.MAX_READ_LIMIT);
    }

    @Test
    @DisplayName("getAuditTrailByAgent clamps the same way")
    void getAuditTrailByAgent_clampsSkipAndLimit() {
        restAuditStore.getAuditTrailByAgent("agent-1", 1, -1, -1);
        verify(auditStore).getEntriesByAgent("agent-1", 1, 0, RestAuditStore.DEFAULT_READ_LIMIT);

        restAuditStore.getAuditTrailByAgent("agent-1", 1, 0, 999_999);
        verify(auditStore).getEntriesByAgent("agent-1", 1, 0, RestAuditStore.MAX_READ_LIMIT);
    }

    @Test
    @DisplayName("getAuditTrailByAgent with null version delegates correctly")
    void getAuditTrailByAgent_nullVersion_delegatesToStore() {
        when(auditStore.getEntriesByAgent("agent-1", null, 0, 100)).thenReturn(List.of());

        var result = restAuditStore.getAuditTrailByAgent("agent-1", null, 0, 100);

        assertEquals(0, result.size());
        verify(auditStore).getEntriesByAgent("agent-1", null, 0, 100);
    }

    @Test
    @DisplayName("getEntryCount delegates to auditStore.countByConversation")
    void getEntryCount_delegatesToStore() {
        when(auditStore.countByConversation("conv-1")).thenReturn(42L);

        long count = restAuditStore.getEntryCount("conv-1");

        assertEquals(42L, count);
        verify(auditStore).countByConversation("conv-1");
    }

    // ==================== G16/G18: integrity sweep ====================

    private static AuditEntry entryAt(String id, long sequence) {
        return new AuditEntry(id, "conv-1", "agent-1", 1, "user-1", "production", 0, "task-1", "test-type", 0, 42L, null, null, null, null,
                List.of("greet"), 0.0, Instant.now(), "v3:deadbeef", null, sequence);
    }

    /**
     * The triage field: a verify sweep reports both a tampered v4 row and an
     * unrecoverable legacy row as INVALID, and those demand opposite reactions —
     * one is an alarm, the other is the expected residue of rows signed over live
     * Java objects that nothing can reconstruct. The problem entry must say which
     * one the operator is looking at.
     */
    @Test
    @DisplayName("a problem entry names the canonical form its HMAC was written with")
    void problemEntriesCarryTheHmacVersion() {
        var legacy = entryAt("id-legacy", 0); // "v3:deadbeef"
        var current = entryAt("id-current", 1).withHmac("v4:" + "0".repeat(64));
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(legacy, current));
        when(auditLedgerService.verifyEntry(any(), any())).thenReturn(AuditVerificationStatus.INVALID);

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(2, report.problems().size());
        assertEquals("v3", report.problems().get(0).hmacVersion(),
                "a pre-v4 INVALID is usually unrecoverable legacy payload, not tampering");
        assertEquals("v4", report.problems().get(1).hmacVersion(),
                "a v4 INVALID is the alarm — something touched a row this release wrote");
    }

    /**
     * A recovered legacy row is intact — it counts as valid, appears in the
     * dedicated {@code recovered} tally so an operator can see how much of the
     * ledger predates v4, and is NOT listed as a problem. Miswiring any of the
     * three turns a healthy legacy ledger into a wall of alarms.
     */
    @Test
    @DisplayName("a VALID_RECOVERED row counts as valid + recovered, and is no problem")
    void recoveredRowCountsAsValid() {
        var direct = entryAt("id-1", 0);
        var recovered = entryAt("id-2", 1);
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(direct, recovered));
        when(auditLedgerService.verifyEntry(eq(direct), any())).thenReturn(AuditVerificationStatus.VALID);
        when(auditLedgerService.verifyEntry(eq(recovered), any())).thenReturn(AuditVerificationStatus.VALID_RECOVERED);

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(2, report.valid(), "a recovered row is proven intact and belongs in valid");
        assertEquals(1, report.recovered());
        assertEquals(0, report.invalid());
        assertTrue(report.problems().isEmpty(), "a recovered row is not a problem to escalate");
    }

    /**
     * The sweep's budget lives on the service; the report's {@code recoverySkipped}
     * must be read back off that same budget after the loop — wiring a literal 0
     * here would make a budget-exhausted sweep look more thorough than it was, and
     * nothing else fails.
     */
    @Test
    @DisplayName("recoverySkipped is read off the sweep's own budget")
    void recoverySkippedIsWiredFromTheBudget() {
        var one = entryAt("id-1", 0);
        var two = entryAt("id-2", 1);
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(one, two));
        // A real, already-exhausted budget: every consume attempt is a skip.
        when(auditLedgerService.newRecoveryBudget()).thenReturn(new AuditRecoveryBudget(0));
        when(auditLedgerService.verifyEntry(any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(1, AuditRecoveryBudget.class).tryConsume();
            return AuditVerificationStatus.INVALID;
        });

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(2, report.recoverySkipped(), "the count must come from the budget the sweep actually used");
        assertEquals(2, report.invalid());
        assertFalse(report.tamperingSuspected(), "nothing was disproven — a budget ran out");
        assertFalse(report.intact(), "an unfinished sweep is still not a clean bill of health");
    }

    /**
     * The point of the endpoint: {@code AuditHmac.verifyHmac} previously had no
     * production caller at all, so a tampered row shipped undetected. A row whose
     * HMAC no longer recomputes must be named in the report.
     */
    @Test
    @DisplayName("a tampered row is reported")
    void tamperedRowIsReported() {
        var good = entryAt("id-1", 0);
        var tampered = entryAt("id-2", 1);
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(good, tampered));
        when(auditLedgerService.verifyEntry(eq(good), any())).thenReturn(AuditVerificationStatus.VALID);
        when(auditLedgerService.verifyEntry(eq(tampered), any())).thenReturn(AuditVerificationStatus.INVALID);

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(2, report.entriesChecked());
        assertEquals(1, report.valid());
        assertEquals(1, report.invalid());
        assertFalse(report.intact());
        assertEquals(1, report.problems().size());
        assertEquals("id-2", report.problems().getFirst().entryId());
        assertEquals(AuditVerificationStatus.INVALID, report.problems().getFirst().status());
    }

    @Test
    @DisplayName("an untouched trail reports intact")
    void untouchedTrailIsIntact() {
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(entryAt("id-1", 0), entryAt("id-2", 1), entryAt("id-3", 2)));

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(ChainStatus.INTACT, report.chainStatus());
        assertTrue(report.intact());
        assertTrue(report.missingSequences().isEmpty());
    }

    /**
     * A per-entry HMAC cannot see a DELETED entry — nothing is left to fail
     * verification. The signed per-conversation sequence is what makes the hole
     * visible.
     */
    @Test
    @DisplayName("deleting a middle entry is detected")
    void deletedMiddleEntryIsDetected() {
        // entry with sequence 1 has been removed from the ledger
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(entryAt("id-1", 0), entryAt("id-3", 2)));

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(0, report.invalid(), "every surviving row still verifies — that is exactly the problem");
        assertEquals(ChainStatus.BROKEN, report.chainStatus());
        assertEquals(List.of(1L), report.missingSequences());
        assertFalse(report.intact());
    }

    /**
     * skip is a query parameter, so a client can send a negative value. MongoDB
     * ignores it; PostgreSQL rejects OFFSET -1 and the request becomes a 500. It is
     * clamped rather than rejected, matching how an out-of-range limit is already
     * handled.
     */
    @Test
    @DisplayName("a negative skip is clamped, not passed to the store")
    void negativeSkipIsClamped() {
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(entryAt("id-1", 0)));
        when(auditStore.countByConversation("conv-1")).thenReturn(1L);

        restAuditStore.verifyConversation("conv-1", -5, 1000);

        verify(auditStore).getEntries("conv-1", 0, 1000);
        verify(auditStore, never()).getEntries(anyString(), intThat(i -> i < 0), anyInt());
    }

    /**
     * An agent-scope sweep spans many conversations, so their sequences interleave
     * and no single ascending run exists — the chain is deliberately reported
     * NOT_APPLICABLE. Requiring INTACT made every clean agent sweep report
     * intact=false, which makes the health bit worthless for the endpoint added for
     * G16.
     */
    @Test
    @DisplayName("a clean agent-scope sweep is intact despite NOT_APPLICABLE")
    void cleanAgentSweepIsIntact() {
        when(auditStore.getEntriesByAgent("agent-1", null, 0, 1000))
                .thenReturn(List.of(entryAt("id-1", 0), entryAt("id-2", 7)));

        var report = restAuditStore.verifyAgent("agent-1", null, 0, 1000);

        assertEquals(ChainStatus.NOT_APPLICABLE, report.chainStatus());
        assertEquals(0, report.invalid());
        assertTrue(report.intact(), "every entry verified; the chain is simply not evaluated at agent scope");
    }

    /**
     * An upgraded deployment has legacy rows with no sequence alongside new
     * sequenced ones — and because the counter is seeded from countByConversation,
     * which counts the legacy rows, the first sequenced entry starts above 0. With
     * the origin anchor that looks exactly like a deleted prefix, so the ledger
     * would accuse an untampered deployment of destroying records. A window that
     * mixes the two simply cannot be judged.
     */
    @Test
    @DisplayName("legacy unsequenced rows alongside sequenced ones report UNAVAILABLE, not BROKEN")
    void mixedSequencedAndUnsequencedIsUnavailable() {
        when(auditStore.getEntries("conv-1", 0, 1000))
                .thenReturn(List.of(entryAt("legacy-1", -1), entryAt("id-6", 5), entryAt("id-7", 6)));

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(ChainStatus.UNAVAILABLE, report.chainStatus());
        assertEquals(List.of(), report.missingSequences(), "0..4 were never assigned, not deleted");
        assertFalse(report.intact(), "an unestablishable chain is not an intact one");
    }

    /**
     * Deleting the FIRST entry leaves no gap behind: 1,2,3 is a perfectly gap-free
     * run. Anchoring the expected range at the smallest sequence present therefore
     * made a prefix deletion completely invisible — the easiest deletion to perform
     * was the one the chain could not see.
     */
    @Test
    @DisplayName("deleting the first entry is detected, not just a middle one")
    void deletedFirstEntryIsDetected() {
        // sequence 0 has been removed; the survivors still form an unbroken run
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(entryAt("id-2", 1), entryAt("id-3", 2)));
        when(auditStore.countByConversation("conv-1")).thenReturn(2L); // the window holds the whole conversation

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(0, report.invalid(), "every survivor still verifies — the hole is only visible via the sequence");
        assertEquals(List.of(0L), report.missingSequences());
        assertEquals(ChainStatus.BROKEN, report.chainStatus());
        assertFalse(report.intact());
    }

    /**
     * getEntries sorts newest-first, so skip == 0 is the most RECENT page, not the
     * conversation's beginning. Using skip as the signal meant verifying the latest
     * few entries of a long conversation anchored at 0 and reported the entire
     * history below them as deleted. The window must be shown to hold the whole
     * conversation before the origin can be assumed.
     */
    @Test
    @DisplayName("a newest-first window that is not the whole conversation is judged on continuity only")
    void partialNewestPageIsNotAnchoredAtOrigin() {
        // the newest two entries of a conversation that holds 100
        when(auditStore.getEntries("conv-1", 0, 2)).thenReturn(List.of(entryAt("id-98", 97), entryAt("id-99", 98)));
        when(auditStore.countByConversation("conv-1")).thenReturn(100L);

        var report = restAuditStore.verifyConversation("conv-1", 0, 2);

        assertEquals(List.of(), report.missingSequences(), "0..96 are simply not in this window");
        assertEquals(ChainStatus.INTACT, report.chainStatus());
    }

    /**
     * The origin anchor must not fire on a paginated sweep: with skip > 0 the
     * earlier entries were legitimately not fetched, so reporting them missing
     * would cry wolf on every second page.
     */
    @Test
    @DisplayName("a paginated window is judged on internal continuity only")
    void paginatedWindowDoesNotReportTheSkippedPrefixAsMissing() {
        when(auditStore.getEntries("conv-1", 5, 1000)).thenReturn(List.of(entryAt("id-6", 5), entryAt("id-7", 6)));

        var report = restAuditStore.verifyConversation("conv-1", 5, 1000);

        assertEquals(List.of(), report.missingSequences(), "0..4 were skipped, not deleted");
        assertEquals(ChainStatus.INTACT, report.chainStatus());
    }

    /**
     * A gap is not the only way to break the chain. If two entries carry the SAME
     * sequence number the chain is ambiguous — one of them may have been replaced,
     * or an entry inserted — and the run has no gap to reveal it. checkChain used
     * to collect duplicates and then decide purely on {@code missing.isEmpty()}, so
     * this reported INTACT and handed an auditor a false assurance.
     */
    @Test
    @DisplayName("duplicate sequence numbers break the chain even with no gap")
    void duplicateSequenceIsDetected() {
        when(auditStore.getEntries("conv-1", 0, 1000))
                .thenReturn(List.of(entryAt("id-1", 0), entryAt("id-2", 1), entryAt("id-3", 1)));

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(0, report.invalid(), "each row still verifies on its own — the chain is what is compromised");
        assertEquals(List.of(), report.missingSequences(), "there is no gap; only the duplicate reveals it");
        assertEquals(List.of(1L), report.duplicateSequences());
        assertEquals(ChainStatus.BROKEN, report.chainStatus());
        assertFalse(report.intact(), "a chain with an ambiguous position must never report intact");
    }

    @Test
    @DisplayName("unsequenced rows report the chain as unavailable, not broken")
    void unsequencedRowsReportUnavailable() {
        when(auditStore.getEntries("conv-1", 0, 1000))
                .thenReturn(List.of(entryAt("id-1", AuditEntry.UNSEQUENCED), entryAt("id-2", AuditEntry.UNSEQUENCED)));

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(ChainStatus.UNAVAILABLE, report.chainStatus());
        assertFalse(report.intact());
    }

    @Test
    @DisplayName("without a signing key the sweep proves nothing")
    void withoutSigningKeyNothingIsProven() {
        when(auditLedgerService.isSigningEnabled()).thenReturn(false);
        when(auditLedgerService.verifyEntry(any(), any())).thenReturn(AuditVerificationStatus.SIGNING_DISABLED);
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(entryAt("id-1", 0)));

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertFalse(report.signingEnabled());
        assertEquals(0, report.valid());
        assertFalse(report.intact());
        assertEquals(AuditVerificationStatus.SIGNING_DISABLED, report.problems().getFirst().status());
    }

    @Test
    @DisplayName("an agent sweep checks HMACs but not the chain")
    void agentSweepSkipsChainCheck() {
        when(auditStore.getEntriesByAgent("agent-1", 1, 0, 1000)).thenReturn(List.of(entryAt("id-1", 0), entryAt("id-3", 2)));

        var report = restAuditStore.verifyAgent("agent-1", 1, 0, 1000);

        assertEquals(ChainStatus.NOT_APPLICABLE, report.chainStatus());
        assertTrue(report.missingSequences().isEmpty(), "sequences interleave across conversations — a gap here means nothing");
    }

    @Test
    @DisplayName("the verification limit is clamped")
    void verificationLimitIsClamped() {
        restAuditStore.verifyConversation("conv-1", 0, Integer.MAX_VALUE);
        verify(auditStore).getEntries("conv-1", 0, RestAuditStore.MAX_VERIFY_LIMIT);
    }

    /**
     * {@code ?limit=0} used to mean "give me the hard maximum" — ten times the
     * documented default, from a caller who asked for nothing. A non-positive limit
     * falls back to the declared {@code @DefaultValue}.
     */
    @Test
    @DisplayName("a non-positive limit falls back to the documented default, not the hard ceiling")
    void nonPositiveLimitFallsBackToTheDefault() {
        restAuditStore.verifyConversation("conv-2", 0, 0);
        verify(auditStore).getEntries("conv-2", 0, RestAuditStore.DEFAULT_VERIFY_LIMIT);

        restAuditStore.verifyConversation("conv-3", 0, -5);
        verify(auditStore).getEntries("conv-3", 0, RestAuditStore.DEFAULT_VERIFY_LIMIT);

        // Pinned as values, not as a relational compare: both are compile-time
        // constants, so `DEFAULT < MAX` can never fail and asserts nothing. The
        // property that matters is that the fallback stays below the hard ceiling,
        // and pinning both numbers makes changing either a deliberate act.
        assertEquals(1_000, RestAuditStore.DEFAULT_VERIFY_LIMIT);
        assertEquals(10_000, RestAuditStore.MAX_VERIFY_LIMIT);
    }

    /**
     * Deriving the expected run from the surviving rows made head deletion free:
     * dropping sequence 0 from {@code [0,1,2]} left {@code [1,2]}, whose own range
     * is gap-free. When the swept page provably starts at the beginning of the
     * conversation, the run is anchored at 0 instead.
     */
    @Test
    @DisplayName("deleting the first entry of a conversation is detected")
    void deletedHeadEntryIsDetected() {
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(entryAt("id-2", 1), entryAt("id-3", 2)));
        // The head anchor engages only when the window provably covers the whole
        // conversation. This test predates that rule — it was written against the
        // earlier skip==0 heuristic, which was unsound because getEntries pages
        // NEWEST-first, so skip==0 is the most recent page rather than the start.
        // Without this stub Mockito returns 0, the anchor never engages, and the
        // report comes back INTACT. Kept rather than deleted as a duplicate of
        // deletedFirstEntryIsDetected because only this one asserts
        // tamperingSuspected().
        when(auditStore.countByConversation("conv-1")).thenReturn(2L);

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(ChainStatus.BROKEN, report.chainStatus());
        assertEquals(List.of(0L), report.missingSequences());
        assertTrue(report.tamperingSuspected());
    }

    /**
     * The head anchor may only be applied when the page provably covers the start
     * of the conversation. A paginated sweep (non-zero skip) starts wherever the
     * store's newest-first window lands, so anchoring at 0 there would report the
     * whole preceding history as deleted.
     */
    @Test
    @DisplayName("a paginated sweep does not anchor the run at zero")
    void paginatedSweepDoesNotAnchorAtZero() {
        when(auditStore.getEntries("conv-1", 10, 1000)).thenReturn(List.of(entryAt("id-11", 10), entryAt("id-12", 11)));

        var report = restAuditStore.verifyConversation("conv-1", 10, 1000);

        assertEquals(ChainStatus.INTACT, report.chainStatus());
        assertTrue(report.missingSequences().isEmpty());
    }

    @Test
    @DisplayName("a full page does not anchor the run at zero either")
    void fullPageDoesNotAnchorAtZero() {
        when(auditStore.getEntries("conv-1", 0, 2)).thenReturn(List.of(entryAt("id-8", 8), entryAt("id-9", 9)));

        var report = restAuditStore.verifyConversation("conv-1", 0, 2);

        assertEquals(ChainStatus.INTACT, report.chainStatus());
        assertTrue(report.missingSequences().isEmpty());
    }

    /**
     * The G18/G20 collision: the ledger's own back-pressure drops consume chain
     * positions, and without attribution the resulting hole reads exactly like a
     * deleted row. A gap the ledger admits to is {@code INCOMPLETE}, not
     * {@code BROKEN} — the report must not accuse the deployment of tampering for
     * entries the deployment never got to write.
     */
    @Test
    @DisplayName("a gap the ledger itself caused is reported as INCOMPLETE, not BROKEN")
    void selfInflictedGapIsNotReportedAsTampering() {
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(entryAt("id-1", 0), entryAt("id-3", 2)));
        when(auditLedgerService.undeliveredSequences("conv-1")).thenReturn(Set.of(1L));

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(ChainStatus.INCOMPLETE, report.chainStatus());
        assertEquals(List.of(1L), report.undeliveredSequences());
        assertTrue(report.missingSequences().isEmpty(), "an entry the ledger dropped is not a deleted entry");
        assertFalse(report.tamperingSuspected());
        assertFalse(report.intact(), "the record is still incomplete");
    }

    @Test
    @DisplayName("an unattributed gap alongside an attributed one still reports BROKEN")
    void unattributedGapStillBreaksTheChain() {
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(entryAt("id-1", 0), entryAt("id-4", 3)));
        when(auditLedgerService.undeliveredSequences("conv-1")).thenReturn(Set.of(1L));

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(ChainStatus.BROKEN, report.chainStatus());
        assertEquals(List.of(2L), report.missingSequences());
        assertEquals(List.of(1L), report.undeliveredSequences());
        assertTrue(report.tamperingSuspected());
    }
}
