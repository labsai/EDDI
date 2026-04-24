/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a simple (no-tools) chat completion against a ChatModel.
 * <p>
 * This handles the "legacy mode" path where the task has no tools configured
 * and just sends the message list directly to the LLM.
 * <p>
 * When a responseSchema parameter is provided and the provider supports it,
 * uses {@link ChatRequest} with {@link ResponseFormat#JSON} to enforce
 * structured output at the API level (more reliable than prompt-only
 * enforcement).
 */
class LegacyChatExecutor {
    private static final Logger LOGGER = Logger.getLogger(LegacyChatExecutor.class);

    /**
     * Result of a legacy chat execution.
     *
     * @param response
     *            the LLM's text response
     * @param responseMetadata
     *            metadata about the response (finish reason, token usage)
     */
    record ChatResult(String response, Map<String, Object> responseMetadata) {
    }

    /**
     * Execute a simple chat completion with retry logic. Uses the simple
     * messages-based API (no structured output enforcement).
     */
    ChatResult execute(ChatModel chatModel, List<ChatMessage> messages, LlmConfiguration.Task task) throws LifecycleException {
        return execute(chatModel, messages, task, false);
    }

    /**
     * Execute a chat completion, optionally using JSON response format. When
     * jsonMode is true, the ChatRequest is built with ResponseFormatType.JSON to
     * enforce structured output at the API level.
     *
     * @param chatModel
     *            the model to chat with
     * @param messages
     *            the full message list (system + history + user)
     * @param task
     *            task configuration (for retry settings)
     * @param jsonMode
     *            if true, set response format to JSON
     * @return result containing response text and metadata
     */
    ChatResult execute(ChatModel chatModel, List<ChatMessage> messages, LlmConfiguration.Task task, boolean jsonMode) throws LifecycleException {

        LOGGER.debug("Executing without tools (legacy mode)" + (jsonMode ? " with JSON response format" : ""));

        ChatResponse messageResponse;
        if (jsonMode) {
            try {
                messageResponse = AgentExecutionHelper.executeWithRetry(() -> {
                    var requestBuilder = ChatRequest.builder().messages(messages);
                    requestBuilder.responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON).build());
                    return chatModel.chat(requestBuilder.build());
                }, task, "Chat model execution (JSON mode)");
            } catch (LifecycleException e) {
                // Provider may not support ResponseFormat.JSON — fall back to standard call.
                // System prompt reinforcement still provides JSON enforcement.
                LOGGER.warn("JSON response format not supported by provider, falling back to standard mode: " + e.getMessage());
                messageResponse = AgentExecutionHelper.executeChatWithRetry(chatModel, messages, task);
            }
        } else {
            messageResponse = AgentExecutionHelper.executeChatWithRetry(chatModel, messages, task);
        }

        var responseContent = messageResponse.aiMessage().text();

        Map<String, Object> responseMetadata = new HashMap<>();
        var metadata = messageResponse.metadata();
        if (metadata != null) {
            if (metadata.finishReason() != null) {
                responseMetadata.put("finishReason", metadata.finishReason().toString());
            }
            if (metadata.tokenUsage() != null) {
                responseMetadata.put("tokenUsage", Map.of("inputTokens", metadata.tokenUsage().inputTokenCount(), "outputTokens",
                        metadata.tokenUsage().outputTokenCount(), "totalTokens", metadata.tokenUsage().totalTokenCount()));
            }
        }

        return new ChatResult(responseContent, responseMetadata);
    }
}
