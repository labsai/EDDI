/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService.StreamingResponseHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("IdempotentStreamingHandler — event forwarding")
class IdempotentStreamingHandlerForwardingTest {

    @Test
    @DisplayName("llm_retry reaches the caller's stream for a keyed request too")
    void llmRetryIsForwarded() throws Exception {
        var delegate = mock(StreamingResponseHandler.class);
        var type = Class.forName("ai.labs.eddi.engine.internal.ConversationService$IdempotentStreamingHandler");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var handler = (StreamingResponseHandler) constructor.newInstance(delegate, mock(TurnIdempotencyService.class), "conv", "key");

        handler.onLlmRetry("invalid_json", 1);

        verify(delegate).onLlmRetry("invalid_json", 1);
    }
}
