package ai.labs.lifecycle;

import ai.labs.memory.IConversationMemory;

/**
 * @author ginccc
 */
public interface ILifecycleManager {
    void executeLifecycle(final IConversationMemory conversationMemory) throws LifecycleException, ConversationStopException;

    void addLifecycleTask(ILifecycleTask lifecycleTask);
}
