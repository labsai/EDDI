package ai.labs.lifecycle;

import ai.labs.memory.IConversationMemory;
import ai.labs.utilities.RuntimeUtilities;

import java.util.LinkedList;
import java.util.List;

public class LifecycleManager implements ILifecycleManager {
    private List<ILifecycleTask> lifecycleTasks;

    public LifecycleManager() {
        lifecycleTasks = new LinkedList<>();
    }

    public void executeLifecycle(final IConversationMemory conversationMemory) throws LifecycleException {
        RuntimeUtilities.checkNotNull(conversationMemory, "conversationMemory");

        try {
            for (ILifecycleTask task : lifecycleTasks) {
                task.executeTask(conversationMemory);
            }
        } catch (LifecycleException e) {
            throw new LifecycleException("Error while executing lifecycle!", e);
        }
    }

    @Override
    public void addLifecycleTask(ILifecycleTask lifecycleTask) {
        RuntimeUtilities.checkNotNull(lifecycleTask, "lifecycleTask");
        lifecycleTasks.add(lifecycleTask);
    }
}
