/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit.model;

import ai.labs.eddi.engine.audit.AuditVerificationStatus;

import java.time.Instant;
import java.util.List;

/**
 * Result of an integrity sweep over a range of the audit ledger.
 * <p>
 * Two independent things are reported, because they catch different attacks:
 * <ul>
 * <li><strong>Per-entry HMAC status</strong> — catches a field edited in
 * place.</li>
 * <li><strong>Chain status</strong> — catches an entry <em>removed</em> or
 * reordered, which a per-entry HMAC cannot see at all. Entries carry a signed,
 * gap-free per-conversation sequence; a missing number is a missing entry, and
 * the surviving neighbours cannot be renumbered to hide it without breaking
 * their own HMACs.</li>
 * </ul>
 * <p>
 * <strong>What the chain check does not cover.</strong> A gap is only visible
 * between two surviving entries, plus at the head when the swept page provably
 * starts at the beginning of the conversation ({@code skip == 0} and the page
 * did not fill its limit, so sequence 0 must be present). Removing the
 * <em>newest</em> entries of a conversation is not detectable at all without a
 * stored high-water mark, which this ledger does not keep — {@code INTACT}
 * therefore means "no hole inside the swept range", not "nothing was ever
 * removed".
 *
 * @param scope
 *            what was swept — {@code "conversation"} or {@code "agent"}
 * @param scopeId
 *            the conversation or agent identifier that was swept
 * @param signingEnabled
 *            whether this deployment has a signing key at all; when false,
 *            every entry reports
 *            {@link AuditVerificationStatus#SIGNING_DISABLED} and the sweep
 *            proves nothing
 * @param entriesChecked
 *            number of entries examined
 * @param valid
 *            entries whose HMAC recomputed correctly
 * @param invalid
 *            entries whose HMAC did not match — tampering, or a key change
 * @param unsigned
 *            entries stored without an HMAC
 * @param chainStatus
 *            outcome of the sequence-continuity check
 * @param missingSequences
 *            sequence numbers absent from an otherwise contiguous range that
 *            this deployment cannot account for — the evidence of removal
 * @param undeliveredSequences
 *            sequence numbers the ledger itself never persisted (queue overflow
 *            or a store outage that ended in the dead-letter sink). They are
 *            holes in the record, but they are <em>this deployment's own</em>
 *            holes, not proof that anything was deleted — see
 *            {@code AuditLedgerService.undeliveredSequences}
 * @param duplicateSequences
 *            sequence numbers seen more than once. Not proof of tampering: two
 *            nodes writing the same conversation concurrently can both seed the
 *            same counter
 * @param problems
 *            per-entry detail for everything that was not {@code VALID}
 * @param verifiedAt
 *            when the sweep ran
 * @since 6.2.0
 */
public record AuditVerificationReport(String scope, String scopeId, boolean signingEnabled, int entriesChecked, int valid, int invalid, int unsigned,
        ChainStatus chainStatus, List<Long> missingSequences, List<Long> undeliveredSequences, List<Long> duplicateSequences,
        List<EntryProblem> problems, Instant verifiedAt) {

    /**
     * Whether the sweep found nothing wrong. False whenever an entry failed
     * verification or the chain is anything other than {@code INTACT} — note that a
     * sweep with no signing key is never {@code intact}, because it checked
     * nothing, and that an {@code INCOMPLETE} chain is still an incomplete record
     * even though it is not evidence of tampering.
     */
    public boolean intact() {
        return signingEnabled && invalid == 0 && unsigned == 0 && chainStatus == ChainStatus.INTACT;
    }

    /**
     * Whether anything in the swept range points at <em>tampering</em>: an entry
     * whose HMAC no longer recomputes, or a gap this deployment cannot account for.
     * An {@code INCOMPLETE} chain (the ledger dropped those entries itself) and a
     * missing signing key are explicitly not tampering.
     */
    public boolean tamperingSuspected() {
        return invalid > 0 || chainStatus == ChainStatus.BROKEN;
    }

    /** Continuity of the per-conversation sequence chain. */
    public enum ChainStatus {
        /** Sequences form a gap-free ascending run. */
        INTACT,
        /**
         * At least one sequence number is missing and cannot be attributed to this
         * ledger's own drops — an entry was removed.
         */
        BROKEN,
        /**
         * Every gap in the range is one this deployment recorded as never persisted
         * (audit queue overflow, or a store outage that ended in the dead-letter sink).
         * The record is incomplete, but not evidence of deletion.
         */
        INCOMPLETE,
        /**
         * The entries carry no sequence, so deletion cannot be detected. Applies to
         * rows written before sequencing existed and to stores that do not persist it
         * ({@code IAuditStore.supportsSequence()}).
         */
        UNAVAILABLE,
        /**
         * Not applicable to this sweep — an agent-scoped range spans many
         * conversations, so a single ascending run is not expected.
         */
        NOT_APPLICABLE
    }

    /**
     * One entry that did not verify cleanly.
     *
     * @param entryId
     *            the audit entry id
     * @param conversationId
     *            the conversation the entry belongs to
     * @param sequence
     *            its position in the conversation chain
     * @param timestamp
     *            when the entry was written
     * @param status
     *            why it is listed here
     */
    public record EntryProblem(String entryId, String conversationId, long sequence, Instant timestamp, AuditVerificationStatus status) {
    }
}
