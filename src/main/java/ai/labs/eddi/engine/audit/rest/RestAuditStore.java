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
        List<AuditEntry> entries = auditStore.getEntries(conversationId, skip, clampLimit(limit));
        return verify("conversation", conversationId, entries, true);
    }

    @Override
    public AuditVerificationReport verifyAgent(String agentId, Integer agentVersion, int skip, int limit) {
        List<AuditEntry> entries = auditStore.getEntriesByAgent(agentId, agentVersion, skip, clampLimit(limit));
        // An agent range spans conversations, so the sequences interleave and a
        // single ascending run is not expected — HMACs only.
        return verify("agent", agentId, entries, false);
    }

    private static int clampLimit(int limit) {
        return limit < 1 ? MAX_VERIFY_LIMIT : Math.min(limit, MAX_VERIFY_LIMIT);
    }

    private AuditVerificationReport verify(String scope, String scopeId, List<AuditEntry> entries, boolean checkChain) {
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
        var duplicates = new ArrayList<Long>();
        ChainStatus chainStatus = checkChain ? checkChain(entries, missing, duplicates) : ChainStatus.NOT_APPLICABLE;

        return new AuditVerificationReport(scope, scopeId, signingEnabled, entries.size(), valid, invalid, unsigned, chainStatus, missing, duplicates,
                problems, Instant.now());
    }

    /**
     * Check that the sequences present form a gap-free ascending run.
     * <p>
     * This is what makes a <em>deletion</em> visible. A per-entry HMAC cannot see a
     * removed row — nothing is left to fail verification — but the sequence is
     * inside the signed payload, so the surviving entries cannot be renumbered to
     * close the gap without invalidating their own HMACs.
     */
    private static ChainStatus checkChain(List<AuditEntry> entries, List<Long> missing, List<Long> duplicates) {
        List<Long> sequences = entries.stream().map(AuditEntry::sequence).filter(s -> s >= 0).sorted().toList();

        if (sequences.isEmpty()) {
            // Pre-sequencing rows, or a store that does not persist the field.
            return entries.isEmpty() ? ChainStatus.INTACT : ChainStatus.UNAVAILABLE;
        }

        Set<Long> seen = new HashSet<>();
        for (Long sequence : sequences) {
            if (!seen.add(sequence)) {
                duplicates.add(sequence);
            }
        }

        long first = sequences.getFirst();
        long last = sequences.stream().max(Comparator.naturalOrder()).orElse(first);
        for (long expected = first; expected <= last; expected++) {
            if (!seen.contains(expected)) {
                missing.add(expected);
            }
        }

        // Duplicates matter as much as gaps. Two entries claiming the same position
        // make the chain ambiguous, which defeats the very property the sequence
        // exists to provide: that a deletion or reordering is detectable. Reporting
        // INTACT here would hand an auditor a false assurance.
        return missing.isEmpty() && duplicates.isEmpty() ? ChainStatus.INTACT : ChainStatus.BROKEN;
    }
}
