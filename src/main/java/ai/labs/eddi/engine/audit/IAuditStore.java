/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;

import java.util.List;

/**
 * Write-once, append-only contract for the immutable audit ledger.
 * <p>
 * Implementations <strong>MUST NOT</strong> provide update or delete
 * operations. This is a deliberate design constraint for EU AI Act compliance:
 * once an audit entry is persisted, it must remain unmodifiable.
 * <p>
 * <strong>GDPR Art. 17(3)(e) exception:</strong> Audit entries are retained
 * under the legal obligation to maintain immutable decision traceability (EU AI
 * Act Articles 17/19). Upon GDPR erasure requests, user identifiers are
 * pseudonymized (replaced with a SHA-256 hash), not deleted. The
 * {@link #pseudonymizeByUserId} method is the sole permitted mutation.
 * <p>
 * both MongoDB and PostgreSQL implementations enforce insert-only semantics
 * apart from that one mutation.
 *
 * @author ginccc
 * @since 6.0.0
 */
public interface IAuditStore {

    /**
     * Append a single audit entry to the ledger.
     *
     * @param entry
     *            the entry to persist (must have a non-null HMAC)
     */
    void appendEntry(AuditEntry entry);

    /**
     * Append a batch of audit entries to the ledger in a single operation.
     *
     * @param entries
     *            the entries to persist
     */
    void appendBatch(List<AuditEntry> entries);

    /**
     * Retrieve audit entries for a conversation, ordered by timestamp descending.
     *
     * @param conversationId
     *            the conversation to query
     * @param skip
     *            number of entries to skip (pagination)
     * @param limit
     *            maximum entries to return
     * @return list of audit entries, newest first
     */
    List<AuditEntry> getEntries(String conversationId, int skip, int limit);

    /**
     * Retrieve audit entries for a specific Agent version, ordered by timestamp
     * descending.
     *
     * @param agentId
     *            the Agent identifier
     * @param agentVersion
     *            the Agent version (null = all versions)
     * @param skip
     *            number of entries to skip
     * @param limit
     *            maximum entries to return
     * @return list of audit entries, newest first
     */
    List<AuditEntry> getEntriesByAgent(String agentId, Integer agentVersion, int skip, int limit);

    /**
     * Count the total number of audit entries for a conversation.
     *
     * @param conversationId
     *            the conversation to count
     * @return the number of entries
     */
    long countByConversation(String conversationId);

    /**
     * Retrieve audit entries for a specific user, ordered by timestamp descending.
     * Used by GDPR Art. 15 export to include processing records.
     *
     * @param userId
     *            the user to query
     * @param skip
     *            number of entries to skip (pagination)
     * @param limit
     *            maximum entries to return
     * @return list of audit entries, newest first
     */
    List<AuditEntry> getEntriesByUserId(String userId, int skip, int limit);

    // === GDPR ===

    /**
     * Pseudonymize all audit entries for a user (GDPR Art. 17). This is the sole
     * permitted mutation on the otherwise immutable ledger, justified by GDPR Art.
     * 17(3)(e) — legal obligation to retain records while removing personally
     * identifiable information.
     *
     * @param userId
     *            the original user identifier
     * @param pseudonym
     *            the pseudonymized replacement (SHA-256 hash)
     * @return number of entries pseudonymized
     */
    long pseudonymizeByUserId(String userId, String pseudonym);

    // === Tamper detection ===

    /**
     * Whether this store round-trips {@link AuditEntry#sequence()}.
     * <p>
     * The sequence is part of the signed payload, so a store that silently dropped
     * it on write would make every one of its rows verify as tampered.
     * Implementations therefore <em>opt in</em>: only when this returns true does
     * {@code AuditLedgerService} assign a real sequence — otherwise entries are
     * signed as {@link AuditEntry#UNSEQUENCED} and stay verifiable, at the cost of
     * not being chained.
     *
     * @return true if the sequence survives a write/read round trip
     */
    default boolean supportsSequence() {
        return false;
    }

    /**
     * The highest chain position this store holds for a conversation, or
     * {@link AuditEntry#UNSEQUENCED} when it holds none (or cannot answer).
     * <p>
     * This is what a sequence counter must be seeded from.
     * {@link #countByConversation} counts <em>persisted rows</em>, which is a
     * different number the moment any position was handed out but never stored:
     * with positions 0-9 issued and 3 and 5 dead-lettered, the count is 8 while the
     * next free position is 10 — so a re-seed from the count hands 8 and 9 out a
     * second time, and {@code /auditstore/verify} grades duplicates exactly like
     * deletions ({@code BROKEN}). The ledger would then accuse the deployment of
     * tampering because of its own bookkeeping. Seeding from {@code max + 1} cannot
     * do that, and unlike the in-memory "undelivered" pin it survives a restart.
     * <p>
     * <strong>KNOWN LIMITATION — sequence allocation is not cluster-safe.</strong>
     * This is a read, not an atomic reservation: entries sit in a node-local queue
     * for up to {@code eddi.audit.flush-interval-seconds} before the store can see
     * them, so two nodes serving consecutive turns of one conversation inside that
     * window both read the same maximum and both hand out the positions after it.
     * Neither backend indexes {@code (conversationId, sequence)} uniquely, so the
     * duplicate is accepted and {@code /auditstore/verify} grades the conversation
     * {@code BROKEN}. <strong>A multi-replica deployment therefore needs
     * conversation affinity for chain integrity today</strong> — individual HMACs
     * still verify; it is the chain-continuity check that is affected.
     * <p>
     * <strong>Deferred fix, and why.</strong> Removing that requirement needs a
     * storage-level atomic counter — PostgreSQL {@code UPDATE ... RETURNING} on a
     * per-conversation counter row, MongoDB {@code findOneAndUpdate} with
     * {@code $inc} — plus a unique {@code (conversationId, sequence)} constraint
     * and a retry on collision. It is a separate change because it moves a store
     * round trip from once per conversation to once per <em>entry</em>, on the
     * pipeline thread, and it is a schema change on both backends. The unique
     * constraint must not be added on its own either: without the allocator that
     * makes collisions impossible, a rejected insert silently drops an audit
     * record, which is worse than the duplicate it prevents — a duplicate at least
     * surfaces as a detectable {@code BROKEN} verdict.
     * <p>
     * <strong>Until then the condition is detected, not silent.</strong>
     * {@code AuditLedgerService.detectForeignSequenceAllocation} re-reads this
     * value after each flush and, when the store already holds a position this node
     * has not handed out yet, logs a WARN naming the conversation and increments
     * {@code eddi_audit_sequence_collisions_total}. A non-zero counter means
     * "multi-replica without conversation affinity". It is a partial detector — two
     * nodes that hand out an identical range are still only caught at verify time —
     * and no substitute for the fix above.
     * <p>
     * The default returns {@link AuditEntry#UNSEQUENCED} so a store that does not
     * implement it falls back to the count — the previous behaviour, and harmless
     * for stores that do not persist a sequence at all.
     *
     * @param conversationId
     *            the conversation to ask about
     * @return the highest stored sequence, or {@link AuditEntry#UNSEQUENCED}
     */
    default long maxSequence(String conversationId) {
        return AuditEntry.UNSEQUENCED;
    }
}
