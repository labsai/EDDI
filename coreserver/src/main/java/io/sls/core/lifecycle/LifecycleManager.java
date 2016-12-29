package io.sls.core.lifecycle;


import io.sls.lifecycle.ILifecycleTask;
import io.sls.lifecycle.LifecycleException;
import io.sls.memory.IConversationMemory;

import java.util.LinkedList;
import java.util.List;

// TODO execution status should be managed by the lifecycle manager, not the tasks themselves
public class LifecycleManager implements ILifecycleManager {
    private List<ILifecycleTask> lifecycleTasks;

    public LifecycleManager() {
        lifecycleTasks = new LinkedList<ILifecycleTask>();
    }

    public void executeLifecycle(final IConversationMemory memory) throws LifecycleException {
        try {
            for (ILifecycleTask task : lifecycleTasks) {
                task.executeTask(memory);
            }

        } catch (LifecycleException e) {
            throw new LifecycleException("Error while executing lifecycle!", e);
        }
    }

    @Override
    public void addLifecycleTask(ILifecycleTask lifecycleTask) {
        lifecycleTasks.add(lifecycleTask);
    }
}
