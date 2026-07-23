/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
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
 * When the {@link JsonResponseFormatPolicy} allows it for the resolved
 * provider, the {@link ChatRequest} is built with {@link ResponseFormat#JSON}
 * to enforce structured output at the API level (more reliable than prompt-only
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
        return execute(chatModel, messages, task, JsonResponseFormatPolicy.DISABLED);
    }

    /**
     * Execute a chat completion, optionally using JSON response format. The format
     * is only set when {@code jsonPolicy} allows it for the resolved provider — a
     * provider that rejects a schemaless JSON format (anthropic, bedrock) is never
     * sent one, so it costs no wasted round trip and no 400.
     * <p>
     * This path never carries tool specifications, so the policy is resolved with
     * {@code toolsInRequest=false}.
     *
     * @param chatModel
     *            the model to chat with
     * @param messages
     *            the full message list (system + history + user)
     * @param task
     *            task configuration (for retry settings)
     * @param jsonPolicy
     *            decides whether this request carries {@code ResponseFormat.JSON};
     *            {@code null} is treated as
     *            {@link JsonResponseFormatPolicy#DISABLED}
     * @return result containing response text and metadata
     */
    ChatResult execute(ChatModel chatModel, List<ChatMessage> messages, LlmConfiguration.Task task, JsonResponseFormatPolicy jsonPolicy)
            throws LifecycleException {

        ResponseFormat responseFormat = jsonPolicy != null ? jsonPolicy.resolve(false) : null;

        LOGGER.debug("Executing without tools (legacy mode)" + (responseFormat != null ? " with JSON response format" : ""));

        ChatResponse messageResponse;
        if (responseFormat != null) {
            try {
                messageResponse = AgentExecutionHelper.executeWithRetry(() -> {
                    var requestBuilder = ChatRequest.builder().messages(messages);
                    requestBuilder.responseFormat(responseFormat);
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

        var aiMessage = messageResponse.aiMessage();
        var responseContent = aiMessage != null ? aiMessage.text() : null;

        Map<String, Object> responseMetadata = new HashMap<>();
        var metadata = messageResponse.metadata();
        if (metadata != null) {
            if (metadata.finishReason() != null) {
                var finishReason = metadata.finishReason().toString();
                responseMetadata.put("finishReason", finishReason);

                // Flag non-normal finish reasons for downstream validation
                if ("CONTENT_FILTER".equalsIgnoreCase(finishReason)) {
                    responseMetadata.put("warning", "content_filter");
                    LOGGER.warnf("LLM response was filtered by content policy (finishReason=%s)", finishReason);
                } else if ("LENGTH".equalsIgnoreCase(finishReason)) {
                    responseMetadata.put("warning", "truncated");
                    LOGGER.warnf("LLM response was truncated due to token limit (finishReason=%s)", finishReason);
                }
            }
            if (metadata.tokenUsage() != null) {
                // Not Map.of: the three counts are boxed Integers and providers legitimately
                // report only some of them (Bedrock and Ollama commonly omit the total), so
                // Map.of would throw NPE and take the whole turn down over telemetry.
                responseMetadata.put("tokenUsage", AgentOrchestrator.tokenUsageMap(metadata.tokenUsage()));
            }
        }

        return new ChatResult(responseContent, responseMetadata);
    }
}
