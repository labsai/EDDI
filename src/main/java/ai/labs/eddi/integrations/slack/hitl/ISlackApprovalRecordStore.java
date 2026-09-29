/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack.hitl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Persisted record of every HITL approval card a Slack integration has posted.
 * <p>
 * A Slack decision (Approve/Reject button) carries the owning integration name
 * and the subject it acts on — a conversation id, or {@code group:<id>} for a
 * group discussion. The request signature and the approver list are bound to
 * that integration, but without this record nothing tied the SUBJECT to it: an
 * approver of integration A who can make Slack send a signed block_actions
 * payload (a hand-built button in any message the A app posts, or a replayed
 * payload with an edited value) could resume ANY paused conversation or group,
 * including ones that belong to another integration, another channel, or no
 * Slack integration at all.
 * <p>
 * The record closes that. It is written just before the card is posted and the
 * interactivity handler refuses a decision unless a live record exists for
 * {@code (integrationName, subject)} whose pause identity is the pause the
 * subject is in right now — so a card can only resolve the pause it was posted
 * for, by the integration that posted it.
 * <p>
 * <b>Card binding.</b> Matching the subject and the current pause is not enough
 * on its own: every card of one subject would carry the same
 * {@code <integration>|<subject>}, so an approver clicking an OLD card (say,
 * "delete file A") while a NEW pause of the same conversation is live ("delete
 * everything") would approve the new action without ever having seen it. Each
 * record therefore carries a random {@link SlackApprovalRecord#cardId() card
 * id}, generated before the record is written and embedded in that card's
 * buttons ({@code <integration>|<subject>|<cardId>}). A decision is accepted
 * only from the card whose id is on the record for the current pause; a button
 * without a card id (a card posted before this binding existed) is refused.
 * <p>
 * It doubles as the idempotency marker for posting ("one card per pause"),
 * which used to be an in-memory cache and so re-posted after every restart.
 * <p>
 * Records expire after {@code eddi.slack.hitl.approval-record-retention}
 * (default 30 days). A decision on a card older than that is refused; the pause
 * can still be resolved through REST or MCP, and a new message in the paused
 * thread posts a fresh card.
 *
 * @since 6.5.0
 */
public interface ISlackApprovalRecordStore {

    /**
     * Pause identity used when the pause timestamp is not known at posting time
     * (the bookmark had not been persisted yet). Such a record only matches a pause
     * that began at or before the record was written — see
     * {@link SlackApprovalRecord#matchesPause}.
     */
    String UNKNOWN_PAUSE = "";

    /**
     * One posted approval card.
     *
     * @param integrationName
     *            the Slack integration that posted the card
     * @param subject
     *            the conversation id, or {@code group:<groupConversationId>}
     * @param pauseEpoch
     *            epoch millis of the pause the card was posted for, as a string, or
     *            {@link #UNKNOWN_PAUSE}
     * @param cardId
     *            the random id embedded in this card's buttons (see
     *            {@link #newCardId()}); a missing id never matches
     * @param approvalChannelId
     *            the channel the card was posted to (diagnostics only)
     * @param createdAt
     *            when the record was written
     * @param expiresAt
     *            when it stops being honoured
     */
    record SlackApprovalRecord(String integrationName, String subject, String pauseEpoch, String cardId,
            String approvalChannelId, Instant createdAt, Instant expiresAt) {

        /**
         * Whether a clicked button carrying {@code presentedCardId} belongs to this
         * card. Constant-time; a missing id on either side never matches, so neither a
         * legacy button nor a record without an id can resolve anything.
         */
        public boolean matchesCard(String presentedCardId) {
            if (cardId == null || cardId.isEmpty() || presentedCardId == null || presentedCardId.isEmpty()) {
                return false;
            }
            return MessageDigest.isEqual(cardId.getBytes(StandardCharsets.UTF_8),
                    presentedCardId.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Whether this card was posted for the pause that began at {@code pausedAt}. An
         * exact pause-epoch match always qualifies. A record written without a known
         * pause ({@link #UNKNOWN_PAUSE}) qualifies only if it was written at or after
         * the pause began — a card posted before a pause existed cannot have been
         * posted for it.
         */
        public boolean matchesPause(Instant pausedAt) {
            if (pausedAt == null) {
                return false;
            }
            if (String.valueOf(pausedAt.toEpochMilli()).equals(pauseEpoch)) {
                return true;
            }
            return UNKNOWN_PAUSE.equals(pauseEpoch) && createdAt != null
                    && !createdAt.isBefore(pausedAt);
        }
    }

    /** Pause identity for a pause timestamp — {@link #UNKNOWN_PAUSE} when null. */
    static String pauseEpochOf(Instant pausedAt) {
        return pausedAt != null ? String.valueOf(pausedAt.toEpochMilli()) : UNKNOWN_PAUSE;
    }

    /**
     * A fresh, unguessable card id: 128 random bits as URL-safe base64 (22
     * characters, never {@code |}), so it can end a button value.
     */
    static String newCardId() {
        byte[] bytes = new byte[16];
        CardIds.RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Holds the shared generator (an interface cannot have private fields). */
    final class CardIds {
        private static final SecureRandom RANDOM = new SecureRandom();

        private CardIds() {
        }
    }

    /**
     * Record that {@code integrationName} is about to post a card for this pause.
     * <p>
     * {@code cardId} is a fresh {@link #newCardId()} that the caller embeds in the
     * card's buttons. When this returns {@code false} no card is posted and the id
     * is discarded.
     *
     * @return {@code true} if this call wrote the record (the caller should post
     *         the card); {@code false} if a live record for the same
     *         {@code (integrationName, subject, pauseEpoch)} already exists (a card
     *         was already posted — do not post another). An expired record counts
     *         as absent and is replaced.
     * @throws RuntimeException
     *             on a storage failure — never reported as {@code false}, so a
     *             failed write cannot be mistaken for "already posted"
     */
    boolean tryRecord(String integrationName, String subject, String pauseEpoch, String cardId,
                      String approvalChannelId);

    /**
     * The live (unexpired) records {@code integrationName} holds for
     * {@code subject}, across all pauses. Empty when this integration never posted
     * a card for the subject.
     */
    List<SlackApprovalRecord> findBySubject(String integrationName, String subject);

    /**
     * Remove one record — used when posting the card failed, so a retry can post
     * again rather than being suppressed until the record expires.
     */
    void delete(String integrationName, String subject, String pauseEpoch);
}
