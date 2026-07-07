/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Static utility for retry logic with exponential backoff.
 * <p>
 * Delegates to
 * {@link RetryConfiguration#executeWithRetry(Callable, RetryConfiguration, String)}
 * for the actual retry implementation. This class provides convenience methods
 * that accept {@link LlmConfiguration.Task} for LLM-specific callers.
 * <p>
 * Used by both {@link LegacyChatExecutor} and {@link AgentOrchestrator}.
 */
class AgentExecutionHelper {

    /**
     * Executes a generic action with retry logic based on task configuration.
     */
    static <T> T executeWithRetry(Callable<T> action, LlmConfiguration.Task task, String actionDescription) throws LifecycleException {
        return RetryConfiguration.executeWithRetry(action, task.getRetry(), actionDescription);
    }

    /**
     * Executes chat model with retry logic based on task configuration.
     */
    static ChatResponse executeChatWithRetry(ChatModel chatModel, List<ChatMessage> messages,
                                             LlmConfiguration.Task task)
            throws LifecycleException {

        return executeWithRetry(() -> chatModel.chat(messages), task, "Chat model execution");
    }
}
