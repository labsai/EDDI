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
 * The email message ledger (email plan §12): one row per message EDDI sends or
 * receives — its state, its retries, and the facts needed to rebuild it.
 * MongoDB and PostgreSQL twins, selected by {@code eddi.datastore.type}.
 * <p>
 * <b>What a row is not:</b> a copy of the message. No body, subject or rendered
 * MIME is ever stored (an inbound row the email approval gate holds keeps a
 * SHA-256 of the raw mail, {@link LedgerEntry#contentHash()}, which reveals
 * nothing of it); a retry re-renders the message from its source (for a
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
        NOTICE,
        /**
         * The sending-health self-test (email channel v3 plan §6.5): the mail an
         * integration sends to its own address. {@code sourceRef} holds the SHA-256 of
         * its single-use token (never the token); the row is {@code SENT} while the
         * test is open and {@code DONE} once the mail came back, with the result in
         * {@code lastError} (see {@code EmailSelfTestResult}).
         */
        SELF_TEST
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
        REFUSED,
        /**
         * Inbound: refused for reviewable reasons only, and held for a reviewer (email
         * channel v3 plan §7.2). The mail is marked processed (moved to the Held
         * folder) so the cursor advances; {@code nextAttemptAt} is when the review
         * expires. A decision moves it to {@link #RECEIVED} (released) or
         * {@link #REFUSED}. Not terminal, but never due for a poll: only a reviewer or
         * the expiry sweep moves it.
         */
        HELD;

        public boolean isTerminal() {
            return switch (this) {
                case PENDING, RECEIVED, CLAIMED, TURN_SUBMITTED, HELD -> false;
                case SENT, FAILED, SUPPRESSED, DONE, REFUSED -> true;
            };
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
     * @param approvalState
     *            inbound only: where the email approval gate's hold of the mail
     *            stands ({@code EmailApprovalNotes}), or null. Its own field, so
     *            that a deferral, a claim or a retry — which rewrite
     *            {@code lastError} — never lose an approval; changed only by
     *            {@link #transitionApproval}
     * @param contentHash
     *            inbound only: SHA-256 (hex) of the raw mail the gate held, so the
     *            mail that runs after the approval is the mail that was approved;
     *            or null. Also set when a mail is held for review (v3 §7.2): only
     *            that mail may run once released
     * @param signed
     *            OUT rows: whether the message left S/MIME-signed, or null
     * @param authVerdict
     *            inbound rows held for review: the sender authentication verdict
     *            when it was held ({@code aligned-pass}, {@code fail}, ...), or
     *            null
     * @param review
     *            inbound rows held for review: where the review stands —
     *            {@code held:<reasons>}, {@code released:<decision>:<reasons>},
     *            {@code discarded:<reasons>} or {@code expired:<reasons>}, reasons
     *            joined by {@code +} — or null. Its own field, so deferrals and the
     *            approval gate, which rewrite {@code lastError}, never lose it;
     *            changed only by {@link #transitionReview}
     * @param heldAt
     *            inbound rows held for review: when the mail was held (the daily
     *            caps count by it), or null
     */
    record LedgerEntry(String id, String integrationId, Kind kind, Direction direction, String messageId, String conversationId, String sourceRef,
            String idempotencyKey, String recipient, State state, int attempts, Instant nextAttemptAt, String claimToken, String providerId,
            String lastError, Instant createdAt, Instant updatedAt, Instant expiresAt, String approvalState, String contentHash, Boolean signed,
            String authVerdict, String review, Instant heldAt) {

        /** A row whose review, if any, records no hold time. */
        public LedgerEntry(String id, String integrationId, Kind kind, Direction direction, String messageId, String conversationId,
                String sourceRef, String idempotencyKey, String recipient, State state, int attempts, Instant nextAttemptAt, String claimToken,
                String providerId, String lastError, Instant createdAt, Instant updatedAt, Instant expiresAt, String approvalState,
                String contentHash, Boolean signed, String authVerdict, String review) {
            this(id, integrationId, kind, direction, messageId, conversationId, sourceRef, idempotencyKey, recipient, state, attempts, nextAttemptAt,
                    claimToken, providerId, lastError, createdAt, updatedAt, expiresAt, approvalState, contentHash, signed, authVerdict, review,
                    null);
        }

        /** A row without a review. */
        public LedgerEntry(String id, String integrationId, Kind kind, Direction direction, String messageId, String conversationId,
                String sourceRef, String idempotencyKey, String recipient, State state, int attempts, Instant nextAttemptAt, String claimToken,
                String providerId, String lastError, Instant createdAt, Instant updatedAt, Instant expiresAt, String approvalState,
                String contentHash, Boolean signed) {
            this(id, integrationId, kind, direction, messageId, conversationId, sourceRef, idempotencyKey, recipient, state, attempts, nextAttemptAt,
                    claimToken, providerId, lastError, createdAt, updatedAt, expiresAt, approvalState, contentHash, signed, null, null);
        }

        /** A row without an approval hold whose {@code signed} is not known. */
        public LedgerEntry(String id, String integrationId, Kind kind, Direction direction, String messageId, String conversationId,
                String sourceRef, String idempotencyKey, String recipient, State state, int attempts, Instant nextAttemptAt, String claimToken,
                String providerId, String lastError, Instant createdAt, Instant updatedAt, Instant expiresAt) {
            this(id, integrationId, kind, direction, messageId, conversationId, sourceRef, idempotencyKey, recipient, state, attempts, nextAttemptAt,
                    claimToken, providerId, lastError, createdAt, updatedAt, expiresAt, null, null, null);
        }

        /** A row without an approval hold whose {@code signed} is not known. */
        public LedgerEntry(String id, String integrationId, Kind kind, Direction direction, String messageId, String conversationId,
                String sourceRef, String idempotencyKey, String recipient, State state, int attempts, Instant nextAttemptAt, String claimToken,
                String providerId, String lastError, Instant createdAt, Instant updatedAt, Instant expiresAt, String approvalState,
                String contentHash) {
            this(id, integrationId, kind, direction, messageId, conversationId, sourceRef, idempotencyKey, recipient, state, attempts, nextAttemptAt,
                    claimToken, providerId, lastError, createdAt, updatedAt, expiresAt, approvalState, contentHash, null);
        }

        /**
         * A row without an approval hold (inbound rows, and every row written before v3
         * phase C2 have no {@code signed} either).
         */
        public LedgerEntry(String id, String integrationId, Kind kind, Direction direction, String messageId, String conversationId,
                String sourceRef, String idempotencyKey, String recipient, State state, int attempts, Instant nextAttemptAt, String claimToken,
                String providerId, String lastError, Instant createdAt, Instant updatedAt, Instant expiresAt, Boolean signed) {
            this(id, integrationId, kind, direction, messageId, conversationId, sourceRef, idempotencyKey, recipient, state, attempts, nextAttemptAt,
                    claimToken, providerId, lastError, createdAt, updatedAt, expiresAt, null, null, signed);
        }

        /**
         * This row with the approval hold, the content hash and the review of
         * {@code other} — every field the row-rewriting copies must carry over.
         */
        public LedgerEntry withApprovalOf(LedgerEntry other) {
            return new LedgerEntry(id, integrationId, kind, direction, messageId, conversationId, sourceRef, idempotencyKey, recipient, state,
                    attempts, nextAttemptAt, claimToken, providerId, lastError, createdAt, updatedAt, expiresAt,
                    other == null ? null : other.approvalState(), other == null ? null : other.contentHash(), signed,
                    other == null ? null : other.authVerdict(), other == null ? null : other.review());
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
     * {@link #complete}, recording whether the message left S/MIME-signed (OUT
     * rows; v3 phase C2). Never the signature bytes.
     */
    boolean complete(String id, String claimToken, State terminal, String providerId, String lastError, Boolean signed, Instant now);

    /**
     * Releases a claimed {@code PENDING} row to wait until {@code nextAttemptAt}
     * without spending an attempt: the claim's increment is given back. For mail
     * held for a reason a retry cannot fix by itself — an S/MIME certificate that
     * {@code sign: required} needs and that is missing or expired — so a held reply
     * does not run out of attempts and fail while its owner renews the certificate.
     *
     * @return whether the claim still held the row
     */
    boolean hold(String id, String claimToken, Instant nextAttemptAt, String reason, Instant now);

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
     * {@link State#RECEIVED} that already name a conversation — or that a reviewer
     * released from review ({@link LedgerEntry#review()} {@code released:…}, with
     * or without a conversation; v3 §7.3) — and whose {@code nextAttemptAt} is at
     * or before {@code now}, oldest first — the mail one integration's poll must
     * find again by its Message-ID and submit once more. Scoped to the integration
     * in the store, so rows nobody polls any more (a deleted integration's, an
     * invalid one's) never take another integration's places; retention removes
     * them.
     */
    List<LedgerEntry> findDueResubmissions(String integrationId, Instant now, int limit);

    /**
     * The most of {@link LedgerEntry#review()} a store keeps (the PostgreSQL column
     * width).
     */
    int MAX_REVIEW = 128;

    /**
     * The most of {@link LedgerEntry#authVerdict()} a store keeps (the PostgreSQL
     * column width).
     */
    int MAX_AUTH_VERDICT = 32;

    /**
     * Moves a row from {@code from} to {@code to}, only if it is still in
     * {@code from}, and sets its {@link LedgerEntry#review()} — the held-mail
     * transitions (v3 §7): held, released, discarded, expired. Of two reviewers (or
     * a reviewer and the expiry sweep) exactly one wins. Sets {@code nextAttemptAt}
     * and {@code lastError} as given; {@link LedgerEntry#authVerdict()} and
     * {@link LedgerEntry#contentHash()} only when not null.
     *
     * @return whether the row was changed
     */
    default boolean transitionReview(String id, State from, State to, String review, String authVerdict, String contentHash,
                                     Instant nextAttemptAt, String lastError, Instant now) {
        return transitionReview(id, from, to, review, authVerdict, contentHash, nextAttemptAt, lastError, null, now);
    }

    /**
     * As the other {@code transitionReview}, also moving {@code expiresAt} to
     * {@code expiresAt} when that is later — a released row must outlive its run.
     * Moving to {@link State#HELD} sets {@link LedgerEntry#heldAt()} to
     * {@code now}.
     */
    boolean transitionReview(String id, State from, State to, String review, String authVerdict, String contentHash, Instant nextAttemptAt,
                             String lastError, Instant expiresAt, Instant now);

    /**
     * Up to {@code limit} {@link Kind#TRIGGER} rows of {@code integrationId} in
     * {@link State#HELD}, oldest first — the review queue.
     */
    List<LedgerEntry> findHeld(String integrationId, int limit);

    /**
     * How many {@link Kind#TRIGGER} rows of {@code integrationId} were held for
     * review at or after {@code since} ({@link LedgerEntry#heldAt()}, whatever was
     * decided since — a discard does not free a place).
     * <ul>
     * <li>{@code aligned} null: all of them;</li>
     * <li>{@code aligned} true: those held with an aligned pass from a sender of
     * {@code senderDomain} — so a forged From cannot use up a real domain's
     * share;</li>
     * <li>{@code aligned} false: those held without one, whatever their From claims
     * — one shared bucket ({@code senderDomain} is ignored).</li>
     * </ul>
     */
    long countHeldSince(String integrationId, String senderDomain, Boolean aligned, Instant since);

    /**
     * The most of {@link LedgerEntry#approvalState()} a store keeps (the PostgreSQL
     * column width).
     */
    int MAX_APPROVAL_STATE = 64;

    /**
     * Sets a row's {@link LedgerEntry#approvalState()} to {@code to}, only if it is
     * still {@code from} ({@code null}: none yet) — so the resume observer and the
     * stranded sweep, which both settle a held mail, settle it once. Sets
     * {@link LedgerEntry#contentHash()} too when {@code contentHash} is not null,
     * and leaves state, {@code lastError} and {@code nextAttemptAt} alone.
     *
     * @return whether the row was changed
     */
    boolean transitionApproval(String id, String from, String to, String contentHash, Instant now);

    /**
     * Up to {@code limit} {@link Kind#TRIGGER} rows whose
     * {@link LedgerEntry#approvalState()} is {@code approvalState} and whose
     * {@code nextAttemptAt} is unset or at or before {@code now}, oldest first —
     * the held mails the stranded sweep reconciles with their conversation.
     */
    List<LedgerEntry> findByApprovalState(String approvalState, Instant now, int limit);

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
     * Whether a {@link Kind#REPLY} row of {@code conversationId} to
     * {@code recipient} (normalised) reached {@link State#SENT} — the
     * {@code anyAuthenticatedParticipant} test of who may continue a thread.
     */
    boolean hasSentReply(String conversationId, String recipient);

    /**
     * How many {@code kind} rows of {@code conversationId} were created at or after
     * {@code since} and not {@link State#SUPPRESSED} — the per-thread reply budget
     * ({@code maxRepliesPerThreadPerHour}), counted cluster-wide.
     */
    long countByConversationSince(String conversationId, Kind kind, Instant since);

    /** Rows addressed to {@code recipient} (normalised), for GDPR export. */
    List<LedgerEntry> findByRecipient(String recipient);

    /**
     * Removes rows addressed to {@code recipient} (normalised), for GDPR erasure.
     */
    long deleteByRecipient(String recipient);
}
