/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit.rest;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.AuditVerificationStatus;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.audit.model.AuditVerificationReport;
import ai.labs.eddi.engine.audit.model.AuditVerificationReport.ChainStatus;
import ai.labs.eddi.engine.audit.model.AuditVerificationReport.EntryProblem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * REST implementation for the audit ledger — delegates to {@link IAuditStore}.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class RestAuditStore implements IRestAuditStore {

    /** Hard ceiling on one verification sweep, whatever the caller asks for. */
    static final int MAX_VERIFY_LIMIT = 10_000;

    /**
     * Page size used when the caller supplies no usable {@code limit}. Mirrors the
     * {@code @DefaultValue("1000")} on {@link IRestAuditStore}: a non-positive
     * limit falls back to the documented default, it does NOT mean "give me the
     * hard maximum" — that sentinel reading is the same footgun
     * {@code RestConversationStore.MIN_RETENTION_DAYS} was introduced to remove.
     */
    static final int DEFAULT_VERIFY_LIMIT = 1_000;

    private final IAuditStore auditStore;
    private final AuditLedgerService auditLedgerService;

    @Inject
    public RestAuditStore(IAuditStore auditStore, AuditLedgerService auditLedgerService) {
        this.auditStore = auditStore;
        this.auditLedgerService = auditLedgerService;
    }

    @Override
    public List<AuditEntry> getAuditTrail(String conversationId, int skip, int limit) {
        return auditStore.getEntries(conversationId, skip, limit);
    }

    @Override
    public List<AuditEntry> getAuditTrailByAgent(String agentId, Integer agentVersion, int skip, int limit) {
        return auditStore.getEntriesByAgent(agentId, agentVersion, skip, limit);
    }

    @Override
    public long getEntryCount(String conversationId) {
        return auditStore.countByConversation(conversationId);
    }

    @Override
    public AuditVerificationReport verifyConversation(String conversationId, int skip, int limit) {
        List<AuditEntry> entries = auditStore.getEntries(conversationId, clampSkip(skip), clampLimit(limit));
        // Anchoring the run at sequence 0 is only sound when the window provably
        // holds the WHOLE conversation. skip == 0 does NOT establish that: getEntries
        // sorts by timestamp DESCENDING ("newest first"), so skip == 0 is the most
        // recent page. Verifying the latest 10 entries of a 1000-entry conversation
        // would otherwise report ~990 deleted. Compare against the stored count
        // instead, which is independent of sort order and paging.
        boolean wholeConversation = entries.size() == auditStore.countByConversation(conversationId);
        return verify("conversation", conversationId, entries, true, wholeConversation);
    }

    @Override
    public AuditVerificationReport verifyAgent(String agentId, Integer agentVersion, int skip, int limit) {
        List<AuditEntry> entries = auditStore.getEntriesByAgent(agentId, agentVersion, clampSkip(skip), clampLimit(limit));
        // An agent range spans conversations, so the sequences interleave and a
        // single ascending run is not expected — HMACs only.
        return verify("agent", agentId, entries, false, false);
    }

    private static int clampLimit(int limit) {
        return limit < 1 ? DEFAULT_VERIFY_LIMIT : Math.min(limit, MAX_VERIFY_LIMIT);
    }

    /**
     * {@code skip} arrives from a query parameter, so it can be negative. The
     * backends disagree about what that means — MongoDB ignores a negative skip
     * while PostgreSQL rejects {@code OFFSET -1} outright — so an unclamped value
     * turns a bad request into a 500 on one backend and silently succeeds on the
     * other. Clamping matches how {@link #clampLimit} already treats an
     * out-of-range limit.
     */
    private static int clampSkip(int skip) {
        return Math.max(skip, 0);
    }

    private AuditVerificationReport verify(String scope, String scopeId, List<AuditEntry> entries, boolean checkChain,
                                           boolean expectRunFromOrigin) {
        boolean signingEnabled = auditLedgerService.isSigningEnabled();
        int valid = 0;
        int invalid = 0;
        int unsigned = 0;
        var problems = new ArrayList<EntryProblem>();

        for (AuditEntry entry : entries) {
            AuditVerificationStatus status = auditLedgerService.verifyEntry(entry);
            switch (status) {
                case VALID -> valid++;
                case INVALID -> invalid++;
                case UNSIGNED -> unsigned++;
                case SIGNING_DISABLED -> {
                    // counted only as a problem — nothing was actually checked
                }
            }
            if (status != AuditVerificationStatus.VALID) {
                problems.add(new EntryProblem(entry.id(), entry.conversationId(), entry.sequence(), entry.timestamp(), status));
            }
        }

        var missing = new ArrayList<Long>();
        var undelivered = new ArrayList<Long>();
        var duplicates = new ArrayList<Long>();
        ChainStatus chainStatus = checkChain
                ? checkChain(entries, missing, undelivered, duplicates, expectRunFromOrigin, undeliveredFor(scopeId))
                : ChainStatus.NOT_APPLICABLE;

        return new AuditVerificationReport(scope, scopeId, signingEnabled, entries.size(), valid, invalid, unsigned, chainStatus, missing,
                undelivered, duplicates, problems, Instant.now());
    }

    /**
     * Chain positions this deployment knows it never persisted. Null-tolerant so a
     * stubbed ledger service cannot turn the sweep into an NPE.
     */
    private Set<Long> undeliveredFor(String conversationId) {
        Set<Long> known = auditLedgerService.undeliveredSequences(conversationId);
        return known == null ? Set.of() : known;
    }

    private static final Logger LOGGER = Logger.getLogger(RestAuditStore.class);

    /**
     * First sequence number a conversation is assigned. {@code AuditLedgerService}
     * seeds each conversation's counter from the stored entry count and hands out
     * {@code getAndIncrement()}, so a conversation's first entry is 0.
     */
    private static final long SEQUENCE_ORIGIN = 0L;

    /**
     * Upper bound on the holes enumerated for one report. The chain is BROKEN the
     * moment anything is missing, so the exhaustive list adds nothing beyond the
     * first few — and without a bound a single tampered sequence near
     * {@code Long.MAX_VALUE} would turn this into an unbounded loop.
     */
    private static final int MAX_REPORTED_MISSING = 1_000;

    /**
     * Check that the sequences present form a gap-free ascending run.
     * <p>
     * This is what makes a <em>deletion</em> visible. A per-entry HMAC cannot see a
     * removed row — nothing is left to fail verification — but the sequence is
     * inside the signed payload, so the surviving entries cannot be renumbered to
     * close the gap without invalidating their own HMACs.
     * <p>
     * Two refinements keep the verdict honest in both directions:
     * <ul>
     * <li>When {@code expectRunFromOrigin}, the expected run is anchored at
     * {@link #SEQUENCE_ORIGIN} rather than at the lowest surviving sequence —
     * otherwise deleting the <em>first</em> entries of a conversation simply moves
     * the anchor and reads as {@code INTACT}.</li>
     * <li>A gap the ledger itself caused (queue overflow / dead-lettered batch,
     * reported by {@code AuditLedgerService.undeliveredSequences}) is listed
     * separately and downgrades the verdict to {@code INCOMPLETE} instead of
     * accusing the deployment of deletion.</li>
     * </ul>
     */
    private static ChainStatus checkChain(List<AuditEntry> entries, List<Long> missing, List<Long> undelivered, List<Long> duplicates,
                                          boolean expectRunFromOrigin, Set<Long> knownUndelivered) {
        if (entries.isEmpty()) {
            return ChainStatus.INTACT;
        }

        // A window that mixes sequenced and unsequenced rows cannot be judged at all.
        // AuditLedgerService seeds a conversation's counter from countByConversation,
        // which COUNTS THE LEGACY ROWS — so after an upgrade the first sequenced entry
        // starts at N, not 0, and the N positions below it were never assigned to
        // anything. Anchoring at the origin would report them as missing and accuse
        // the deployment of deleting audit records it never had. Unsequenced rows also
        // make a genuine deletion undetectable, so the honest answer for a mixed window
        // is that the chain cannot be established.
        if (entries.stream().anyMatch(e -> e.sequence() < 0)) {
            return ChainStatus.UNAVAILABLE;
        }

        List<Long> sequences = entries.stream().map(AuditEntry::sequence).sorted().toList();

        Set<Long> seen = new HashSet<>();
        for (Long sequence : sequences) {
            if (!seen.add(sequence)) {
                duplicates.add(sequence);
            }
        }

        // Anchoring the expected run at the smallest sequence PRESENT would make a
        // deletion at the very start invisible: drop sequence 0 and 1,2,3 is still a
        // gap-free run. When the window starts at the conversation's beginning the
        // run must therefore begin at SEQUENCE_ORIGIN, so a missing prefix is
        // reported like any other hole. For a paginated window (skip > 0) the
        // preceding entries were legitimately not fetched, so only continuity
        // within the window can be judged.
        long first = expectRunFromOrigin ? SEQUENCE_ORIGIN : sequences.getFirst();
        long last = sequences.stream().max(Comparator.naturalOrder()).orElse(first);
        // A tampered or corrupt sequence can be arbitrarily large, and anchoring the
        // run at the origin means `first` is 0 — so a single bogus row near
        // Long.MAX_VALUE would make this walk effectively forever (and `expected++`
        // would overflow at the top). Cap the enumeration: the chain is already
        // known to be BROKEN once anything is missing, and listing the first N holes
        // is all a report needs. Anything beyond that is noise the caller cannot act
        // on individually.
        for (long expected = first; expected <= last && missing.size() < MAX_REPORTED_MISSING; expected++) {
            if (seen.contains(expected)) {
                continue;
            }
            // A hole the ledger itself recorded as never persisted is this
            // deployment's own gap, not evidence that a row was deleted.
            if (knownUndelivered.contains(expected)) {
                undelivered.add(expected);
            } else {
                missing.add(expected);
            }
        }
        if (missing.size() >= MAX_REPORTED_MISSING) {
            LOGGER.warnf("Chain verification stopped after %d missing sequences; the range under inspection spans %d..%d",
                    MAX_REPORTED_MISSING, first, last);
        }

        // Duplicates matter as much as gaps. Two entries claiming the same position
        // make the chain ambiguous, which defeats the very property the sequence
        // exists to provide: that a deletion or reordering is detectable. Reporting
        // INTACT here would hand an auditor a false assurance.
        if (!missing.isEmpty() || !duplicates.isEmpty()) {
            return ChainStatus.BROKEN;
        }
        // Nothing unaccounted for is missing. Gaps the ledger itself caused leave the
        // record incomplete without being evidence of deletion.
        return undelivered.isEmpty() ? ChainStatus.INTACT : ChainStatus.INCOMPLETE;
    }
}
