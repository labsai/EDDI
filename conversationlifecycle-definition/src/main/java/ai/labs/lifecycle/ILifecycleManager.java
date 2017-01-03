package ai.labs.lifecycle;

import io.sls.memory.IConversationMemory;

/**
 * @author ginccc
 */
public interface ILifecycleManager {
    void executeLifecycle(final IConversationMemory conversationMemory) throws LifecycleException;

    void addLifecycleTask(ILifecycleTask lifecycleTask);
}
