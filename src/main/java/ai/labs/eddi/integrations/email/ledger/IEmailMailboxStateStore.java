/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import java.time.Instant;
import java.util.Optional;

/**
 * Per-mailbox polling state, next to the {@link IEmailMessageLedger}: one small
 * document per email integration with the IMAP cursor, the connection back-off
 * and the day's spend — plus a set of one-off notice keys.
 * <p>
 * <b>Why a store of its own.</b> The ledger holds one row per <em>message</em>
 * and expires them after {@code eddi.email.ledger-retention}; the cursor is one
 * value per <em>mailbox</em> that must outlive every message. The cluster
 * key-value buckets are node-local in single-node mode and lost on restart, so
 * a cursor kept there would make every restart re-read the folder. MongoDB and
 * PostgreSQL twins, selected by {@code eddi.datastore.type}. No address,
 * subject or body is stored here.
 *
 * @since 6.6.0
 */
public interface IEmailMailboxStateStore {

    /**
     * The state of one mailbox.
     *
     * @param integrationId
     *            the email channel integration
     * @param folder
     *            the folder the cursor belongs to
     * @param uidValidity
     *            the folder's {@code UIDVALIDITY} when the cursor was written; 0
     *            when none was recorded yet
     * @param lastUid
     *            every message with a UID up to this one is settled
     * @param since
     *            mail delivered before this instant is never processed (the moment
     *            the integration first polled), or null for none
     * @param failures
     *            consecutive failed polls
     * @param backoffUntil
     *            no poll before this instant, or null
     * @param lastError
     *            the last failure's category and short reason (no secret), or null
     * @param budgetDay
     *            the UTC day {@code spentUsd} belongs to ({@code yyyy-MM-dd}), or
     *            null
     * @param spentUsd
     *            what this integration's turns cost on {@code budgetDay}
     * @param updatedAt
     *            last change
     */
    record MailboxState(String integrationId, String folder, long uidValidity, long lastUid, Instant since, int failures, Instant backoffUntil,
            String lastError, String budgetDay, double spentUsd, Instant updatedAt) {
    }

    Optional<MailboxState> read(String integrationId);

    /**
     * Writes the cursor and clears the failure count, back-off and last error
     * (upsert).
     */
    void saveCursor(String integrationId, String folder, long uidValidity, long lastUid, Instant since, Instant now);

    /** Records a failed poll (upsert); the cursor is left as it is. */
    void recordFailure(String integrationId, int failures, Instant backoffUntil, String lastError, Instant now);

    /**
     * Adds {@code amountUsd} to the spend of {@code day}, starting from zero when
     * the stored day is another one — atomically, so concurrent turn completions on
     * several nodes all count.
     *
     * @return the day's spend after the addition
     */
    double addSpend(String integrationId, String day, double amountUsd, Instant now);

    /**
     * Records that the notice {@code noticeKey} (e.g. {@code 2026-10-25:budget})
     * went out for this integration, once cluster-wide.
     *
     * @return true for the one caller that claimed it
     */
    boolean claimNotice(String integrationId, String noticeKey, Instant now);

    /** Removes everything held for the integration (it was deleted). */
    void delete(String integrationId);
}
