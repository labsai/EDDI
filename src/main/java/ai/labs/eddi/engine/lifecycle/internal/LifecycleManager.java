package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;

import java.util.LinkedList;
import java.util.List;

import static ai.labs.eddi.utils.LifecycleUtilities.createComponentKey;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

public class LifecycleManager implements ILifecycleManager {
    private static final String KEY_ACTIONS = "actions";

    private final List<ILifecycleTask> lifecycleTasks;
    private final IComponentCache componentCache;
    private final IResourceStore.IResourceId packageId;

    public LifecycleManager(IComponentCache componentCache, IResourceStore.IResourceId packageId) {
        this.componentCache = componentCache;
        this.packageId = packageId;

        lifecycleTasks = new LinkedList<>();
    }

    public void executeLifecycle(final IConversationMemory conversationMemory, List<String> lifecycleTaskTypes)
            throws LifecycleException, ConversationStopException {

        checkNotNull(conversationMemory, "conversationMemory");

        List<ILifecycleTask> lifecycleTasks;
        if (isNullOrEmpty(lifecycleTaskTypes)) {
            lifecycleTasks = this.lifecycleTasks;
        } else {
            lifecycleTasks = getLifecycleTasks(lifecycleTaskTypes);
        }

        for (int index = 0; index < lifecycleTasks.size(); index++) {
            ILifecycleTask task = lifecycleTasks.get(index);
            if (Thread.currentThread().isInterrupted()) {
                throw new LifecycleException.LifecycleInterruptedException("Execution was interrupted!");
            }

            try {
                var components = componentCache.getComponentMap(task.getId());
                var componentKey = createComponentKey(packageId.getId(), packageId.getVersion(), index);
                var component = components.getOrDefault(componentKey, null);

                task.execute(conversationMemory, component);

                checkIfStopConversationAction(conversationMemory);
            } catch (LifecycleException e) {
                throw new LifecycleException("Error while executing lifecycle!", e);
            }
        }
    }

    private List<ILifecycleTask> getLifecycleTasks(List<String> lifecycleTaskTypes) {
        List<ILifecycleTask> ret = new LinkedList<>();
        for (int i = 0; i < this.lifecycleTasks.size(); i++) {
            ILifecycleTask task = this.lifecycleTasks.get(i);
            if (lifecycleTaskTypes.stream().anyMatch(type -> type.startsWith(task.getType()))) {
                ret.addAll(this.lifecycleTasks.subList(i, this.lifecycleTasks.size()));
                break;
            }
        }

        return ret;
    }

    private void checkIfStopConversationAction(IConversationMemory conversationMemory) throws ConversationStopException {
        IData<List<String>> actionData = conversationMemory.getCurrentStep().getLatestData(KEY_ACTIONS);
        if (actionData != null) {
            var result = actionData.getResult();
            if (result != null && result.contains(IConversation.STOP_CONVERSATION)) {
                throw new ConversationStopException();
            }
        }
    }

    @Override
    public void addLifecycleTask(ILifecycleTask lifecycleTask) {
        checkNotNull(lifecycleTask, "lifecycleTask");
        lifecycleTasks.add(lifecycleTask);
    }
}
