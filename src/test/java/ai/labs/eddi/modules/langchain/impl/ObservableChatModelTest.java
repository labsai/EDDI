package ai.labs.eddi.modules.langchain.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ObservableChatModel} — the provider-agnostic timeout + logging wrapper.
 */
class ObservableChatModelTest {

    private static final ChatResponse MOCK_RESPONSE = ChatResponse.builder()
            .aiMessage(AiMessage.from("Hello!"))
            .build();

    @Test
    void wrapIfNeeded_noObservabilityParams_returnsOriginalModel() {
        ChatModel original = mock(ChatModel.class);

        ChatModel result = ObservableChatModel.wrapIfNeeded(original, "openai", null, null, null);

        assertSame(original, result, "Should return unwrapped model when no observability params set");
    }

    @Test
    void wrapIfNeeded_withTimeout_returnsWrappedModel() {
        ChatModel original = mock(ChatModel.class);

        ChatModel result = ObservableChatModel.wrapIfNeeded(original, "openai", "5000", null, null);

        assertNotSame(original, result);
        assertInstanceOf(ObservableChatModel.class, result);
    }

    @Test
    void wrapIfNeeded_withLogRequests_returnsWrappedModel() {
        ChatModel original = mock(ChatModel.class);

        ChatModel result = ObservableChatModel.wrapIfNeeded(original, "openai", null, "true", null);

        assertInstanceOf(ObservableChatModel.class, result);
    }

    @Test
    void wrapIfNeeded_withLogResponses_returnsWrappedModel() {
        ChatModel original = mock(ChatModel.class);

        ChatModel result = ObservableChatModel.wrapIfNeeded(original, "openai", null, null, "true");

        assertInstanceOf(ObservableChatModel.class, result);
    }

    @Test
    void wrapIfNeeded_invalidTimeout_returnsOriginalModel() {
        ChatModel original = mock(ChatModel.class);

        ChatModel result = ObservableChatModel.wrapIfNeeded(original, "openai", "notanumber", null, null);

        assertSame(original, result, "Invalid timeout should be ignored, no wrapping");
    }

    @Test
    void chat_delegatesToUnderlyingModel() {
        ChatModel delegate = mock(ChatModel.class);
        var request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("Hi")))
                .build();
        when(delegate.chat(request)).thenReturn(MOCK_RESPONSE);

        ChatModel wrapped = ObservableChatModel.wrapIfNeeded(delegate, "openai", null, "true", "true");
        ChatResponse response = wrapped.chat(request);

        assertEquals("Hello!", response.aiMessage().text());
        verify(delegate).chat(request);
    }

    @Test
    void chat_withTimeout_delegatesSuccessfully() {
        ChatModel delegate = mock(ChatModel.class);
        var request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("Hi")))
                .build();
        when(delegate.chat(request)).thenReturn(MOCK_RESPONSE);

        // 10 second timeout — more than enough for a mock
        ChatModel wrapped = ObservableChatModel.wrapIfNeeded(delegate, "openai", "10000", null, null);
        ChatResponse response = wrapped.chat(request);

        assertEquals("Hello!", response.aiMessage().text());
        verify(delegate).chat(request);
    }

    @Test
    void chat_withTimeout_throwsOnSlow() {
        ChatModel delegate = mock(ChatModel.class);
        var request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("Hi")))
                .build();
        when(delegate.chat(request)).thenAnswer(inv -> {
            Thread.sleep(5000); // simulate slow model
            return MOCK_RESPONSE;
        });

        // Very short timeout
        ChatModel wrapped = ObservableChatModel.wrapIfNeeded(delegate, "openai", "50", null, null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> wrapped.chat(request));
        assertTrue(ex.getMessage().contains("timed out"));
    }
}
