/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The email message ledger (email plan §12): one row per message EDDI sends or,
 * in later phases, receives — its state, its retries, and the facts needed to
 * rebuild it. MongoDB and PostgreSQL twins, selected by
 * {@code eddi.datastore.type}.
 * <p>
 * <b>What a row is not:</b> a copy of the message. No body, subject or rendered
 * MIME is ever stored; a retry re-renders the message from its source (for a
 * notification, the pause it is about), so personal data lives in one place and
 * an erased conversation leaves nothing readable behind here. The one address a
 * row holds is its {@link LedgerEntry#recipient()}, which GDPR erasure and
 * export reach through {@link #findByRecipient} / {@link #deleteByRecipient}.
 * <p>
 * <b>Exactly once, cluster-wide.</b> Two unique keys make every insert a claim:
 * {@link LedgerEntry#idempotencyKey()} (one notification per pause per
 * recipient however many pods observe the pause) and
 * {@code (integrationId, direction, messageId)} (the inbound dedup key of Phase
 * 2). {@link #insertIfAbsent} returns {@code false} for the loser.
 * <p>
 * <b>Claims.</b> A row is worked on by whoever holds its
 * {@link LedgerEntry#claimToken()}: the inserting pod for the immediate first
 * attempt, then a sweeper that won {@link #claimDue}. A claim moves
 * {@code nextAttemptAt} to the end of a lease, so a pod that dies mid-send
 * leaves a row the next sweep picks up, and every state change is conditional
 * on the token, so a claim that was taken over cannot overwrite the new owner's
 * result.
 * <p>
 * <b>Later phases extend, not replace.</b> Inbound mail adds the states
 * {@code RECEIVED → TURN_SUBMITTED → DONE | REFUSED} and the stranded-turn
 * query; replies add {@link Kind#REPLY}; relay webhooks add single-use replay
 * tokens. The row shape and the claim protocol here are the ones they build on.
 */
public interface IEmailMessageLedger {

    /** The integration id of mail the deployment sends itself (no channel). */
    String SYSTEM_INTEGRATION = "system";

    /**
     * The most of {@link LedgerEntry#providerId()} a store keeps (the PostgreSQL
     * column width).
     */
    int MAX_PROVIDER_ID = 255;

    /**
     * The most of {@link LedgerEntry#lastError()} a store keeps (the PostgreSQL
     * column width).
     */
    int MAX_LAST_ERROR = 512;

    /**
     * {@code value} cut to at most {@code max} characters, never between the two
     * halves of a surrogate pair. Every store caps the two free-text fields with it
     * on every write: a value over the column width made PostgreSQL refuse the
     * update, which left the row claimed until its lease ran out and retried it
     * forever.
     */
    static String cap(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }

    /** In or out. */
    enum Direction {
        IN, OUT
    }

    /** What a message is for. */
    enum Kind {
        /** A HITL approval notification (Phase 1). */
        NOTIFY,
        /** A conversation-mode reply (Phase 3). */
        REPLY
    }

    /** Where a message is in its life. Outbound only, for now. */
    enum State {
        /** Waiting for its first or a further attempt. */
        PENDING,
        /** Accepted by the relay. Terminal. */
        SENT,
        /** Given up on: a permanent refusal, or the last attempt failed. Terminal. */
        FAILED,
        /**
         * Deliberately not sent — the reason that made it necessary is gone (the pause
         * was decided) or the recipient is suppressed. Terminal.
         */
        SUPPRESSED;

        public boolean isTerminal() {
            return this != PENDING;
        }
    }

    /**
     * One ledger row.
     *
     * @param id
     *            the row id (random)
     * @param integrationId
     *            the channel integration, or {@link #SYSTEM_INTEGRATION}
     * @param kind
     *            what the message is for
     * @param direction
     *            in or out
     * @param messageId
     *            the RFC 5322 Message-ID ({@code <…@…>}), minted before the first
     *            attempt and kept across retries
     * @param conversationId
     *            the conversation the message belongs to, or null
     * @param sourceRef
     *            what the message is rebuilt from: for {@link Kind#NOTIFY} the
     *            pause id; for {@link Kind#REPLY} the conversation step
     * @param idempotencyKey
     *            unique; for a notification a digest of kind, conversation, pause
     *            and recipient
     * @param recipient
     *            the normalised address it goes to (outbound), or null
     * @param state
     *            its state
     * @param attempts
     *            send attempts started so far
     * @param nextAttemptAt
     *            when a sweeper may claim it next (the end of the current lease
     *            while claimed)
     * @param claimToken
     *            the current claim, or null when unclaimed
     * @param providerId
     *            what the relay answered on acceptance (SMTP reply, capped)
     * @param lastError
     *            the last failure's category and short message, or null — never a
     *            body or a recipient
     * @param createdAt
     *            when it was written
     * @param updatedAt
     *            when it last changed
     * @param expiresAt
     *            when retention removes it
     */
    record LedgerEntry(String id, String integrationId, Kind kind, Direction direction, String messageId, String conversationId, String sourceRef,
            String idempotencyKey, String recipient, State state, int attempts, Instant nextAttemptAt, String claimToken, String providerId,
            String lastError, Instant createdAt, Instant updatedAt, Instant expiresAt) {

        public LedgerEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(integrationId, "integrationId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        /** No recipient: rows reach log lines. */
        @Override
        public String toString() {
            return "LedgerEntry[id=" + id + ", kind=" + kind + ", " + direction + ", state=" + state + ", attempts=" + attempts + "]";
        }
    }

    /**
     * Writes {@code entry} unless a row with its idempotency key, or with its
     * {@code (integrationId, direction, messageId)}, already exists — atomically,
     * so of two pods inserting the same notification exactly one gets {@code true}.
     */
    boolean insertIfAbsent(LedgerEntry entry);

    Optional<LedgerEntry> findById(String id);

    Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey);

    /**
     * Claims up to {@code limit} {@link State#PENDING} rows of {@code kind} whose
     * {@code nextAttemptAt} is due — each kind has its own worker, so a sweeper
     * never holds a row it cannot send: each gets a new claim token, its
     * {@code nextAttemptAt} moves to {@code leaseUntil} and its attempt count goes
     * up by one. Each row is claimed by a conditional update, so two sweepers
     * running at once never claim the same row.
     *
     * @return the claimed rows, as they are after the claim
     */
    List<LedgerEntry> claimDue(Kind kind, Instant now, Instant leaseUntil, int limit);

    /**
     * Moves a claimed row to a terminal state, only if {@code claimToken} still
     * holds its claim.
     *
     * @return whether the row was changed
     */
    boolean complete(String id, String claimToken, State terminal, String providerId, String lastError, Instant now);

    /**
     * Releases a claimed row for another attempt at {@code nextAttemptAt}, only if
     * {@code claimToken} still holds its claim.
     *
     * @return whether the row was changed
     */
    boolean reschedule(String id, String claimToken, Instant nextAttemptAt, String lastError, Instant now);

    /**
     * How many {@code kind} rows to {@code recipient} were created at or after
     * {@code since} and not {@link State#SUPPRESSED} — the cluster-wide count a
     * per-recipient rate limit reads. Suppressed rows do not count, so a flood that
     * is being refused does not keep the window full for ever.
     */
    long countCreatedSince(String recipient, Kind kind, Instant since);

    /** Rows not yet terminal, store-wide (the pending gauge). */
    long countPending();

    /** Removes rows whose retention ended at or before {@code now}. */
    long deleteExpired(Instant now);

    /** Rows addressed to {@code recipient} (normalised), for GDPR export. */
    List<LedgerEntry> findByRecipient(String recipient);

    /**
     * Removes rows addressed to {@code recipient} (normalised), for GDPR erasure.
     */
    long deleteByRecipient(String recipient);
}
