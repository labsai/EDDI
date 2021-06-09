package ai.labs.lifecycle;

import ai.labs.memory.IConversationMemory;

import java.util.List;

/**
 * @author ginccc
 */
public interface ILifecycleManager {
    void executeLifecycle(final IConversationMemory conversationMemory, List<String> lifecycleTaskTypes)
            throws LifecycleException, ConversationStopException;

    void addLifecycleTask(ILifecycleTask lifecycleTask);
}
