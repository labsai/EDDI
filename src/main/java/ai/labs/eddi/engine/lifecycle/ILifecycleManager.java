package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;

import java.util.List;

/**
 * @author ginccc
 */
public interface ILifecycleManager {
    void executeLifecycle(final IConversationMemory conversationMemory, List<String> lifecycleTaskTypes)
            throws LifecycleException, ConversationStopException;

    void addLifecycleTask(ILifecycleTask lifecycleTask);
}
