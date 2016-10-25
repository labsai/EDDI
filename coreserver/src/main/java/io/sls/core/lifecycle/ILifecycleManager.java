package io.sls.core.lifecycle;

import io.sls.memory.IConversationMemory;

/**
 * @author ginccc
 */
public interface ILifecycleManager {
    void executeLifecycle(final IConversationMemory conversation) throws LifecycleException;

    void addLifecycleTask(ILifecycleTask lifecycleTask);
}
