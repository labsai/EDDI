/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.memory.ConversationFencedException;
import ai.labs.eddi.engine.model.DeadLetterEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Dead-letter reasons")
class DeadLetterClassifierTest {

    @Test
    @DisplayName("a fenced write — also when wrapped — is 'fenced' with both tokens")
    void fenced() {
        var c = DeadLetterClassifier.classify(new ExecutionException(new ConversationFencedException("c1", 5, 9)));
        assertEquals(DeadLetterEntry.REASON_FENCED, c.reason());
        assertEquals(Map.of("token", 5L, "storedFence", 9L), c.fence());
    }

    @Test
    @DisplayName("a timeout is 'timeout'; anything else 'failed'")
    void timeoutAndFailed() {
        assertEquals(DeadLetterEntry.REASON_TIMEOUT, DeadLetterClassifier.classify(new RuntimeException(new TimeoutException())).reason());
        assertEquals(DeadLetterEntry.REASON_FAILED, DeadLetterClassifier.classify(new IllegalStateException("x")).reason());
        assertNull(DeadLetterClassifier.classify(new IllegalStateException("x")).fence());
    }

    @Test
    @DisplayName("why an entry cannot be replayed: secret input, input not captured, not a turn")
    void notReplayableReasons() {
        assertNull(new DeadLetterEntry("1", "c", "e", 0, null, Map.of("agentId", "a", "input", "hi")).notReplayableReason());
        assertEquals(DeadLetterEntry.NOT_REPLAYABLE_SECRET,
                new DeadLetterEntry("1", "c", "e", 0, null, Map.of("agentId", "a", "secretInput", true)).notReplayableReason());
        assertEquals(DeadLetterEntry.NOT_REPLAYABLE_NOT_CAPTURED,
                new DeadLetterEntry("1", "c", "e", 0, null, Map.of("agentId", "a")).notReplayableReason());
        assertEquals(DeadLetterEntry.NOT_REPLAYABLE_NOT_A_TURN, new DeadLetterEntry("1", "c", "e", 0, null, null).notReplayableReason());
    }
}
