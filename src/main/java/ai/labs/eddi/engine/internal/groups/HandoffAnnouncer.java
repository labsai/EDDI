/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.HandoffRunState;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import org.jboss.logging.Logger;

/**
 * Turns the handoffs a turn's tool calls queued on the live discussion into
 * {@code team_handoff} events. Tools have no listener reference (the assembly
 * context carries none), so, like artifact writes, they queue and the turn
 * executor drains after the turn — whatever the turn's own outcome, so a
 * timeout after a filed handoff does not swallow the event.
 * <p>
 * Always drains, even with a null listener, so the queue never grows without
 * bound. A listener that throws must not break the turn.
 *
 * @since 6.7.0
 */
public final class HandoffAnnouncer {

    private static final Logger LOGGER = Logger.getLogger(HandoffAnnouncer.class);

    private HandoffAnnouncer() {
    }

    public static void announce(GroupConversation gc, GroupDiscussionEventListener listener) {
        HandoffRunState state = gc != null ? gc.getHandoffState() : null;
        if (state == null) {
            return;
        }
        for (HandoffRunState.PendingEvent event : state.drainEvents()) {
            if (listener == null) {
                continue;
            }
            try {
                listener.onTeamHandoff(new GroupConversationEventSink.TeamHandoffEvent(event.handoffId(), event.targetAlias(), event.targetGroupId(),
                        event.outcome(), event.mode(), event.trigger(), event.subject()));
            } catch (RuntimeException e) {
                LOGGER.debugf("Listener rejected team_handoff event: %s", e.getMessage());
            }
        }
    }
}
