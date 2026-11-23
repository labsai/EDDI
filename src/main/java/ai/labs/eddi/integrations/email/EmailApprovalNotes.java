/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import java.util.Set;

/**
 * Where the email approval gate's hold of an inbound mail stands (email plan v3
 * §8.1): the ledger row's own {@code approvalState}, and the matching
 * {@code lastError} notes.
 * <p>
 * A new conversation pauses before {@code CONVERSATION_START}; the mail's own
 * turn cannot be submitted into a paused conversation, so its row is closed as
 * {@link #AWAITING_START}, with a SHA-256 of the raw mail
 * ({@code contentHash}). When the pause is approved the row goes back to
 * {@code RECEIVED} as {@link #START_APPROVED}, the next poll fetches the mail
 * again — the one with that hash — and submits it into the now-started
 * conversation without a second pause. A mail that does not hash the same is
 * not the mail that was approved: it waits for approval again. A rejection
 * leaves the row {@link #REJECTED}; a start that failed, a pause that was
 * cancelled and a conversation that is gone end it {@link #START_FAILED},
 * {@link #START_CANCELLED} and {@link #CONVERSATION_GONE}. The resume observer
 * settles a hold when the decision is made; the stranded sweep settles what it
 * missed ({@code EmailInboundSweeper}).
 * <p>
 * The approval lives in {@code approvalState}, not in {@code lastError}: a
 * deferral, a retry or a claim rewrites {@code lastError}, and must not undo
 * it.
 */
public final class EmailApprovalNotes {

    /** The conversation was started paused; the mail waits for the decision. */
    public static final String AWAITING_START = "email_gate:awaiting_start";
    /** The paused start was approved: submit the mail, do not hold it again. */
    public static final String START_APPROVED = "email_gate:start_approved";
    /** An approver rejected the mail before its agent ran. */
    public static final String REJECTED = "email_gate:rejected";
    /** The start was approved, but {@code CONVERSATION_START} ended in an error. */
    public static final String START_FAILED = "email_gate:start_failed";
    /** The pause was cancelled (or timed out without a verdict): nothing ran. */
    public static final String START_CANCELLED = "email_gate:start_cancelled";
    /** The held conversation no longer exists. */
    public static final String CONVERSATION_GONE = "email_gate:conversation_gone";

    /**
     * Notes of {@code DONE} rows whose mail did not (or not yet) run to an answer:
     * they are no earlier contact for {@code firstContact}.
     */
    public static final Set<String> NOT_A_CONTACT = Set.of(AWAITING_START, REJECTED, START_FAILED, START_CANCELLED, CONVERSATION_GONE,
            "awaiting_human", "turn_skipped", "turn_failed");

    private EmailApprovalNotes() {
    }
}
