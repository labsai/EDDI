/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.reply;

import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reply is read from the turn's context, which only the detailed snapshot
 * projection keeps: the default one strips {@code context} from every output.
 * Pinned against the real converter, so a reply path that asked for the default
 * projection would find no mail step and send nothing.
 */
class EmailReplyThreadSnapshotTest {

    private static ConversationMemorySnapshot stored() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.READY);
        var start = new ConversationOutput();
        start.put("output", List.of("Hello."));
        var step = new ConversationOutput();
        step.put("output", List.of("It ships Monday."));
        step.put("context", new HashMap<>(Map.of("mail", new HashMap<>(Map.of("messageId", "<q1@x.example>", "authentication", "aligned-pass")))));
        snapshot.getConversationOutputs().add(start);
        snapshot.getConversationOutputs().add(step);
        return snapshot;
    }

    @Test
    @DisplayName("the detailed projection keeps the mail step; the default one does not")
    void contextOnlyInDetailedProjection() {
        var detailed = ConversationMemoryUtilities.convertSimpleConversationMemory(stored(), true, false);
        assertEquals(1, EmailReplyThread.mailStep(detailed.getConversationOutputs(), "<q1@x.example>"));
        assertEquals("Hello.\n\nIt ships Monday.", EmailReplyThread.replyText(detailed.getConversationOutputs(), 1));

        var plain = ConversationMemoryUtilities.convertSimpleConversationMemory(stored(), false, false);
        assertEquals(-1, EmailReplyThread.mailStep(plain.getConversationOutputs(), "<q1@x.example>"));
    }
}
