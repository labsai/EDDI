package io.sls.core.lifecycle;

import io.sls.memory.IConversationMemory;

/**
 * Created by IntelliJ IDEA.
 * User: jarisch
 * Date: 10.02.2012
 * Time: 00:41:54
 */
public interface ILifecycleManager {
    void executeLifecycle(final IConversationMemory conversation) throws LifecycleException;

    void addLifecycleTask(ILifecycleTask lifecycleTask);
}
