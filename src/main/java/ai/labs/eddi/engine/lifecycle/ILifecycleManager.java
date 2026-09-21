/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;

import java.util.List;

/**
 * @author ginccc
 */
public interface ILifecycleManager {
    void executeLifecycle(final IConversationMemory conversationMemory, List<String> lifecycleTaskTypes)
            throws LifecycleException, ConversationStopException, ConversationPauseException;

    /**
     * Execute the lifecycle workflow with an optional event sink for SSE streaming.
     * The sink receives task_start/task_complete events for each lifecycle task.
     */
    default void executeLifecycle(final IConversationMemory conversationMemory, List<String> lifecycleTaskTypes, ConversationEventSink eventSink)
            throws LifecycleException, ConversationStopException, ConversationPauseException {
        // Default: set sink on memory and delegate to standard method
        if (eventSink != null) {
            conversationMemory.setEventSink(eventSink);
        }
        executeLifecycle(conversationMemory, lifecycleTaskTypes);
    }

    void executeLifecycleFromIndex(IConversationMemory conversationMemory, int startFromAbsoluteIndex)
            throws LifecycleException, ConversationStopException, ConversationPauseException;

    void addLifecycleTask(ILifecycleTask lifecycleTask);
}
