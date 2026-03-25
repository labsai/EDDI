package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes streaming chat completion — tokens are emitted in real-time via the
 * {@link ConversationEventSink}.
 * <p>
 * This class blocks until the full response is received (via CountDownLatch) so
 * that the lifecycle workflow can proceed synchronously while tokens stream to
 * the client.
 */
class StreamingLegacyChatExecutor {
    private static final Logger LOGGER = Logger.getLogger(StreamingLegacyChatExecutor.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 120;

    /**
     * Execute a streaming chat completion, emitting tokens via the event sink.
     *
     * @param streamingModel
     *            the streaming-capable chat model
     * @param messages
     *            the full message list (system + history + user)
     * @param eventSink
     *            the sink to emit token events to
     * @return the full accumulated response text (for memory storage)
     */
    String execute(StreamingChatModel streamingModel, List<ChatMessage> messages, ConversationEventSink eventSink) {

        LOGGER.debug("Executing with streaming (legacy mode)");

        var latch = new CountDownLatch(1);
        var fullResponse = new StringBuilder();
        var errorRef = new AtomicReference<Throwable>();

        var chatRequest = ChatRequest.builder().messages(messages).build();

        streamingModel.chat(chatRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                fullResponse.append(partialResponse);
                try {
                    eventSink.onToken(partialResponse);
                } catch (Exception e) {
                    LOGGER.warnf("Error sending token event: %s", e.getMessage());
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        try {
            if (!latch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("Streaming chat timed out");
                return fullResponse.toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Streaming chat was interrupted");
        }

        if (errorRef.get() != null) {
            LOGGER.errorf("Streaming chat error: %s", errorRef.get().getMessage());
            throw new RuntimeException("Streaming chat failed", errorRef.get());
        }

        return fullResponse.toString();
    }
}
