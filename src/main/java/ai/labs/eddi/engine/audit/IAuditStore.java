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
     * do that, and it survives a restart and a second cluster node, which the
     * in-memory "undelivered" pin does not.
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
