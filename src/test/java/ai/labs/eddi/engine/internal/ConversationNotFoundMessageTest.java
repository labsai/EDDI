/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService.ConversationNotFoundException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The "no such conversation" message carries a caller-supplied id, and does not
 * stay server-side.
 *
 * <p>
 * {@code RestAgentEngineStreaming} echoes a
 * {@link ConversationNotFoundException}'s text into an SSE {@code error} event
 * so the client gets a machine-readable {@code conversation_not_found} code
 * rather than an opaque blob. That makes the conversation id — a path
 * parameter, so entirely the caller's — part of a response body and of every
 * log line the exception reaches. It has to be sanitized where it is built, and
 * only one of the two construction sites was doing it.
 * </p>
 */
@DisplayName("ConversationNotFoundException message safety")
class ConversationNotFoundMessageTest {

    /**
     * What a request to {@code /agents/conv%00%08%1F%E2%80%A8%0D%0AX/stream}
     * delivers once the container has percent-decoded the path.
     */
    private static final String HOSTILE_ID = "conv\u0000\u0008\u001F\u2028\r\nX";

    @Test
    @DisplayName("the id is stripped of control characters before it reaches the message")
    void sanitizesTheConversationId() {
        var thrown = assertThrows(ConversationNotFoundException.class,
                () -> ConversationStepRunner.checkConversationMemoryNotNull(null, HOSTILE_ID));

        String message = thrown.getMessage();
        assertTrue(message.contains("conv"), "the readable part of the id survives: " + message);

        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            assertFalse(Character.isISOControl(c), "no ISO control character may reach the message, found U+"
                    + String.format("%04X", (int) c) + " in: " + message);
            assertFalse(c == '\u2028' || c == '\u2029',
                    "U+2028/U+2029 terminate a line in JavaScript and this reaches a browser: " + message);
        }
    }

    @Test
    @DisplayName("a present conversation memory throws nothing")
    void passesThroughWhenPresent() {
        assertDoesNotThrow(
                () -> ConversationStepRunner.checkConversationMemoryNotNull(mock(IConversationMemory.class), HOSTILE_ID));
    }
}
