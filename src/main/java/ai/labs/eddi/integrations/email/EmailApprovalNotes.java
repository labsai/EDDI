/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import java.util.Set;

/**
 * The ledger notes ({@code lastError}) of an inbound mail the email approval
 * gate held before its agent ran (email plan v3 §8.1).
 * <p>
 * A new conversation pauses before {@code CONVERSATION_START}; the mail's own
 * turn cannot be submitted into a paused conversation, so its row is closed as
 * {@link #AWAITING_START}. When the pause is approved the row goes back to
 * {@code RECEIVED} as {@link #START_APPROVED}, the next poll fetches the mail
 * again by its Message-ID and submits it into the now-started conversation
 * without a second pause. A rejection leaves it {@link #REJECTED}.
 */
public final class EmailApprovalNotes {

    /** The conversation was started paused; the mail waits for the decision. */
    public static final String AWAITING_START = "email_gate:awaiting_start";
    /** The paused start was approved: submit the mail, do not hold it again. */
    public static final String START_APPROVED = "email_gate:start_approved";
    /** An approver rejected the mail before its agent ran. */
    public static final String REJECTED = "email_gate:rejected";

    /**
     * Notes of {@code DONE} rows whose mail did not (or not yet) run to an answer:
     * they are no earlier contact for {@code firstContact}.
     */
    public static final Set<String> NOT_A_CONTACT = Set.of(AWAITING_START, REJECTED, "awaiting_human", "turn_skipped", "turn_failed");

    private EmailApprovalNotes() {
    }
}
