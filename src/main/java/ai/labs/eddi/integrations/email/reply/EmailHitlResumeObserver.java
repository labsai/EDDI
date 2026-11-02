/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.reply;

import ai.labs.eddi.engine.events.HitlPauseCommittedEvent;
import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Email-side observer of the HITL events (email plan §16, "in email
 * conversations"), as {@code SlackHitlResumeObserver} is Slack's:
 * <ul>
 * <li>{@link HitlPauseCommittedEvent} — a conversation-mode email conversation
 * paused on a mail's turn: the sender gets the pause notice in the thread
 * (output so far and the pause reason, never the tool or its arguments);</li>
 * <li>{@link HitlResumeCompletedEvent} — the pause was decided, cancelled or
 * timed out: the sender gets the outcome and, on approval, the
 * continuation.</li>
 * </ul>
 * The event is fired on the node that committed the pause or ran the resume;
 * the reply goes through the ledger like every other, so a second observer or a
 * retry on another node sends it once. A conversation that is not an email
 * conversation in conversation mode is ignored. Async and best-effort: nothing
 * here can affect the engine.
 */
@ApplicationScoped
public class EmailHitlResumeObserver {

    private static final Logger LOGGER = Logger.getLogger(EmailHitlResumeObserver.class);

    private final EmailReplySender replies;

    @Inject
    public EmailHitlResumeObserver(EmailReplySender replies) {
        this.replies = replies;
    }

    void onPauseCommitted(@ObservesAsync HitlPauseCommittedEvent event) {
        try {
            replies.onPauseCommitted(event.conversationId(), event.pauseId(), Instant.now());
        } catch (RuntimeException e) {
            LOGGER.warnf("Email pause notice for conversation %s failed: %s", sanitize(event.conversationId()), e.getMessage());
        }
    }

    void onResumeCompleted(@ObservesAsync HitlResumeCompletedEvent event) {
        try {
            replies.onResumeCompleted(event.conversationId(), event.verdict(), Instant.now());
        } catch (RuntimeException e) {
            LOGGER.warnf("Email continuation for conversation %s failed: %s", sanitize(event.conversationId()), e.getMessage());
        }
    }
}
