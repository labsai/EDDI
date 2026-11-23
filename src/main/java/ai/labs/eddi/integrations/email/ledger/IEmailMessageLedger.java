/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The email message ledger (email plan §12): one row per message EDDI sends or
 * receives — its state, its retries, and the facts needed to rebuild it.
 * MongoDB and PostgreSQL twins, selected by {@code eddi.datastore.type}.
 * <p>
 * <b>What a row is not:</b> a copy of the message. No body, subject or rendered
 * MIME is ever stored; a retry re-renders the message from its source (for a
 * notification, the pause it is about), so personal data lives in one place and
 * an erased conversation leaves nothing readable behind here. The one address a
 * row holds is its {@link LedgerEntry#recipient()} — the counterpart of the
 * message: whom an outbound row goes to, whom an inbound row came from — which
 * GDPR erasure and export reach through {@link #findByRecipient} /
 * {@link #deleteByRecipient}.
 * <p>
 * <b>Exactly once, cluster-wide.</b> Two unique keys make every insert a claim:
 * {@link LedgerEntry#idempotencyKey()} (one notification per pause per
 * recipient however many pods observe the pause) and
 * {@code (integrationId, direction, messageId)} (the inbound dedup key of
 * trigger mode). {@link #insertIfAbsent} returns {@code false} for the loser.
 * <p>
 * <b>Claims.</b> A row is worked on by whoever holds its
 * {@link LedgerEntry#claimToken()}: the inserting pod for the immediate first
 * attempt, then a sweeper that won {@link #claimDue}. A claim moves
 * {@code nextAttemptAt} to the end of a lease, so a pod that dies mid-send
 * leaves a row the next sweep picks up, and every state change is conditional
 * on the token, so a claim that was taken over cannot overwrite the new owner's
 * result.
 * <p>
 * <b>Inbound.</b> A trigger-mode mail is a {@link Kind#TRIGGER} row,
 * {@link Direction#IN}, keyed by its Message-ID, moving
 * {@code RECEIVED → CLAIMED → TURN_SUBMITTED → DONE | REFUSED} through
 * conditional {@link #transition}s. While a row is {@code RECEIVED},
 * {@code nextAttemptAt} is when a deferred mail may be tried again; while it is
 * {@code TURN_SUBMITTED}, it is the deadline after which the turn counts as
 * stranded ({@link #findDue}).
 * <p>
 * <b>Replies.</b> A conversation-mode reply is a {@link Kind#REPLY} row,
 * {@link Direction#OUT}, under an idempotency key derived from conversation,
 * step and reply kind, worked like a notification (claim, lease, complete,
 * reschedule). Its Message-ID is what a correspondent's next mail names in
 * {@code In-Reply-To}/{@code References}, so the row is also the thread index
 * ({@link #findByMessage} with {@link Direction#OUT}). Relay webhooks will add
 * single-use replay tokens.
 * <p>
 * <b>Reply recipients.</b> A reply that copies the thread
 * ({@code replyAudience: thread}, email v2 plan §9.5) has more than one
 * address. The row's own {@link LedgerEntry#recipient()} stays its {@code To};
 * every recipient, the {@code To} included, is also a {@link ReplyRecipient} —
 * the child store {@code email_reply_recipients} — written when the reply is
 * queued and narrowed when an attempt drops a {@code Cc}. It is what
 * {@link #hasSentReply} reads (a {@code Cc} of a reply that was sent is a
 * participant of its thread) and what GDPR export and erasure reach by address.
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

    /** How an address received a reply. */
    enum RecipientRole {
        TO, CC
    }

    /**
     * One recipient of a reply row (email v2 plan §9.5).
     *
     * @param rowId
     *            the reply's ledger row
     * @param integrationId
     *            its integration
     * @param conversationId
     *            its conversation, or null
     * @param address
     *            the normalised address
     * @param role
     *            {@code To} or {@code Cc}
     * @param createdAt
     *            when it was written
     * @param expiresAt
     *            when retention removes it (the row's own)
     */
    record ReplyRecipient(String rowId, String integrationId, String conversationId, String address, RecipientRole role, Instant createdAt,
            Instant expiresAt) {
        public ReplyRecipient {
            Objects.requireNonNull(rowId, "rowId");
            Objects.requireNonNull(integrationId, "integrationId");
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        /** No address: recipients reach log lines. */
        @Override
        public String toString() {
            return "ReplyRecipient[row=" + rowId + ", " + role + "]";
        }
    }

    /** What a message is for. */
    enum Kind {
        /** A HITL approval notification. */
        NOTIFY,
        /** A conversation-mode reply. */
        REPLY,
        /** An inbound mail that starts a turn (trigger mode). */
        TRIGGER,
        /**
         * A one-off notice to an integration's owners (a budget was reached), sent
         * once, never retried.
         */
        NOTICE
    }

    /** Where a message is in its life. */
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
        SUPPRESSED,
        /**
         * Inbound: recorded, not yet handed to a turn — on its way, or deferred
         * (temporary authentication result, a budget, a quota) until
         * {@code nextAttemptAt}.
         */
        RECEIVED,
        /**
         * Inbound: claimed by one poll, nothing done outside EDDI yet — no conversation
         * started, no turn submitted, the mail not marked. A claim past its deadline
         * (the node died) goes back to {@link #RECEIVED} and the mail, still unmarked
         * in the polled folder, is fetched again.
         */
        CLAIMED,
        /**
         * Inbound: past the point of no return — the conversation is being started or
         * the turn submitted; waiting for it to finish.
         */
        TURN_SUBMITTED,
        /** Inbound: the turn ran (or was resolved by the stranded sweep). Terminal. */
        DONE,
        /**
         * Inbound: deliberately not processed, the reason in {@code lastError}.
         * Terminal.
         */
        REFUSED;

        public boolean isTerminal() {
            return this != PENDING && this != RECEIVED && this != CLAIMED && this != TURN_SUBMITTED;
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
     *            the normalised address it goes to (outbound) or came from
     *            (inbound: the {@code From}, authenticated or not), or null
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
     * @param threadKey
     *            for a {@link Kind#REPLY}, the thread it answers —
     *            {@code sha256(integrationId + "\n" + threadRoot)}, see
     *            {@code EmailThreads} — so a thread's replies are counted together
     *            whoever they went to; null on every other row and on replies
     *            written before it existed (email v2 plan §7.7)
     */
    record LedgerEntry(String id, String integrationId, Kind kind, Direction direction, String messageId, String conversationId, String sourceRef,
            String idempotencyKey, String recipient, State state, int attempts, Instant nextAttemptAt, String claimToken, String providerId,
            String lastError, Instant createdAt, Instant updatedAt, Instant expiresAt, String threadKey) {

        /** A row of no thread. */
        public LedgerEntry(String id, String integrationId, Kind kind, Direction direction, String messageId, String conversationId,
                String sourceRef, String idempotencyKey, String recipient, State state, int attempts, Instant nextAttemptAt, String claimToken,
                String providerId, String lastError, Instant createdAt, Instant updatedAt, Instant expiresAt) {
            this(id, integrationId, kind, direction, messageId, conversationId, sourceRef, idempotencyKey, recipient, state, attempts,
                    nextAttemptAt, claimToken, providerId, lastError, createdAt, updatedAt, expiresAt, null);
        }

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

    /**
     * Removes rows — and reply recipients — whose retention ended at or before
     * {@code now}.
     */
    long deleteExpired(Instant now);

    /** The row of one message, by the inbound dedup key. */
    Optional<LedgerEntry> findByMessage(String integrationId, Direction direction, String messageId);

    /**
     * Any outbound row, of any integration (the system mailer included), whose
     * Message-ID is {@code messageId} — how a mail referring to something EDDI sent
     * is recognised (loop guard).
     */
    Optional<LedgerEntry> findOutboundByMessageId(String messageId);

    /**
     * Moves a row from {@code from} to {@code to}, only if it is still in
     * {@code from} — the inbound claim. Sets {@code nextAttemptAt} and
     * {@code lastError} as given (null clears them), {@code conversationId} only
     * when not null, and adds one to {@code attempts} when {@code countAttempt}.
     *
     * @return whether the row was changed (false: someone else moved it first)
     */
    boolean transition(String id, State from, State to, String conversationId, Instant nextAttemptAt, String lastError, boolean countAttempt,
                       Instant now);

    /**
     * Up to {@code limit} rows of {@code kind} in {@code state} whose
     * {@code nextAttemptAt} is at or before {@code now}, oldest first — the
     * stranded-turn sweep reads {@link State#TURN_SUBMITTED} rows past their
     * deadline. Reading claims nothing; resolve each with {@link #transition}.
     */
    List<LedgerEntry> findDue(Kind kind, State state, Instant now, int limit);

    /**
     * Up to {@code limit} {@link Kind#TRIGGER} rows of {@code integrationId} in
     * {@link State#RECEIVED} that already name a conversation and whose
     * {@code nextAttemptAt} is at or before {@code now}, oldest first — the mail
     * one integration's poll must find again by its Message-ID and submit once
     * more. Scoped to the integration in the store, so rows nobody polls any more
     * (a deleted integration's, an invalid one's) never take another integration's
     * places; retention removes them.
     */
    List<LedgerEntry> findDueResubmissions(String integrationId, Instant now, int limit);

    /**
     * How many {@link Kind#TRIGGER} rows of {@code integrationId} from
     * {@code sender} created at or after {@code since} reached a turn
     * ({@link State#TURN_SUBMITTED} or {@link State#DONE}) — the per-sender daily
     * budget.
     */
    long countInboundTurns(String integrationId, String sender, Instant since);

    /**
     * The first ({@code latest} false) or the latest ({@code latest} true) by
     * {@code createdAt} {@link Kind#TRIGGER} row, {@link Direction#IN}, of
     * {@code conversationId} that reached a turn ({@link State#TURN_SUBMITTED} or
     * {@link State#DONE}), of any integration — the mail that started a thread (its
     * sender may continue it) and the mail a reply answers (email plan §11). One
     * indexed lookup however many rows the conversation has.
     */
    Optional<LedgerEntry> findInboundTurn(String conversationId, boolean latest);

    /**
     * Whether a {@link Kind#REPLY} row of {@code conversationId} that reached
     * {@link State#SENT} went to {@code recipient} (normalised) — as its
     * {@code To}, or as a {@code Cc} the child store still holds for it. The test
     * of who may continue a thread ({@code anyAuthenticatedParticipant}, and every
     * participant under {@code replyAudience: thread}). A recipient of a reply that
     * was never sent, or a {@code Cc} an attempt dropped, does not count.
     */
    boolean hasSentReply(String conversationId, String recipient);

    /**
     * How many {@link Kind#REPLY} rows of {@code conversationId} reached
     * {@link State#SENT}.
     */
    long countSentReplies(String conversationId);

    /**
     * How many of those went to {@code recipient} (normalised) — as their
     * {@code To}, or as a {@code Cc} the child store still holds. Equal to
     * {@link #countSentReplies} exactly when the address read every reply the
     * conversation sent: the test of who may continue a thread conversation under
     * {@code replyAudience: thread} (email v2 plan §9.4).
     */
    long countSentRepliesTo(String conversationId, String recipient);

    /**
     * The ids of the inbound mails of {@code conversationId} that ran a turn there:
     * every one of them is in the history the agent reads, answered or not.
     */
    Set<String> turnMailIds(String conversationId);

    /**
     * The ids of the inbound mails of {@code conversationId} that a
     * {@link State#SENT} {@link Kind#REPLY} row went to {@code recipient}
     * (normalised) for — as its {@code To}, or as a {@code Cc} the child store
     * still holds. A mail answered by a pause notice and again by its resume reply
     * counts once, which is the point: the test of who may continue a thread
     * conversation is that every {@link #turnMailIds} entry is in this set — per
     * turn, not per reply row (email v2 plan §9.4).
     */
    Set<String> answeredMailIdsSentTo(String conversationId, String recipient);

    /**
     * The inbound mail id a reply row's {@code sourceRef}
     * ({@code <inbound row id>|<kind>|<discriminator>}) names.
     */
    static String inboundIdOf(String sourceRef) {
        if (sourceRef == null) {
            return "";
        }
        int bar = sourceRef.indexOf('|');
        return bar < 0 ? sourceRef : sourceRef.substring(0, bar);
    }

    /**
     * Records the recipients of a reply row. Idempotent per
     * {@code (rowId, address)}: a recipient written twice is kept once.
     */
    void addReplyRecipients(List<ReplyRecipient> recipients);

    /** The recipients of {@code rowId}, {@code To} first, then in written order. */
    List<ReplyRecipient> findReplyRecipients(String rowId);

    /**
     * Removes recipients of {@code rowId} — the {@code Cc}s an attempt no longer
     * sends to (suppressed, or narrowed by the settings), so they do not count as
     * participants.
     *
     * @return how many were removed
     */
    long removeReplyRecipients(String rowId, Collection<String> addresses);

    /**
     * Every reply recipient entry of {@code address} (normalised), for GDPR export.
     */
    List<ReplyRecipient> findReplyRecipientsByAddress(String address);

    /**
     * How many {@code kind} rows of {@code conversationId} were created at or after
     * {@code since} and not {@link State#SUPPRESSED} — the per-thread reply budget
     * ({@code maxRepliesPerThreadPerHour}), counted cluster-wide.
     */
    long countByConversationSince(String conversationId, Kind kind, Instant since);

    /**
     * How many {@code kind} rows of {@code integrationId} with {@code threadKey}
     * were created at or after {@code since} and not {@link State#SUPPRESSED} — the
     * per-thread reply budget ({@code maxRepliesPerThreadPerHour}) across every
     * conversation of one mail thread, counted cluster-wide. Rows without a thread
     * key never count.
     */
    long countByThreadSince(String integrationId, String threadKey, Kind kind, Instant since);

    /** Rows addressed to {@code recipient} (normalised), for GDPR export. */
    List<LedgerEntry> findByRecipient(String recipient);

    /**
     * Removes rows addressed to {@code recipient} (normalised), with their reply
     * recipients, and every reply recipient entry of that address on other rows —
     * for GDPR erasure.
     */
    long deleteByRecipient(String recipient);
}
