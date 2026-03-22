package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a simple (no-tools) chat completion against a ChatModel.
 * <p>
 * This handles the "legacy mode" path where the task has no tools configured
 * and just sends the message list directly to the LLM.
 */
class LegacyChatExecutor {
    private static final Logger LOGGER = Logger.getLogger(LegacyChatExecutor.class);

    /**
     * Result of a legacy chat execution.
     *
     * @param response         the LLM's text response
     * @param responseMetadata metadata about the response (finish reason, token
     *                         usage)
     */
    record ChatResult(String response, Map<String, Object> responseMetadata) {
    }

    /**
     * Execute a simple chat completion with retry logic.
     *
     * @param chatModel the model to chat with
     * @param messages  the full message list (system + history + user)
     * @param task      task configuration (for retry settings)
     * @return result containing response text and metadata
     */
    ChatResult execute(ChatModel chatModel, List<ChatMessage> messages, LlmConfiguration.Task task)
            throws LifecycleException {

        LOGGER.debug("Executing without tools (legacy mode)");
        var messageResponse = AgentExecutionHelper.executeChatWithRetry(chatModel, messages, task);
        var responseContent = messageResponse.aiMessage().text();

        Map<String, Object> responseMetadata = new HashMap<>();
        var metadata = messageResponse.metadata();
        if (metadata != null) {
            if (metadata.finishReason() != null) {
                responseMetadata.put("finishReason", metadata.finishReason().toString());
            }
            if (metadata.tokenUsage() != null) {
                responseMetadata.put("tokenUsage", Map.of(
                        "inputTokens", metadata.tokenUsage().inputTokenCount(),
                        "outputTokens", metadata.tokenUsage().outputTokenCount(),
                        "totalTokens", metadata.tokenUsage().totalTokenCount()));
            }
        }

        return new ChatResult(responseContent, responseMetadata);
    }
}
