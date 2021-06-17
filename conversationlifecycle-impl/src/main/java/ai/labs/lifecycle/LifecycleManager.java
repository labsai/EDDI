package ai.labs.lifecycle;

import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;

import java.util.LinkedList;
import java.util.List;

import static ai.labs.utilities.RuntimeUtilities.checkNotNull;
import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;

public class LifecycleManager implements ILifecycleManager {
    private static final String KEY_ACTIONS = "actions";
    private final List<ILifecycleTask> lifecycleTasks;

    public LifecycleManager() {
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

        for (ILifecycleTask task : lifecycleTasks) {
            if (Thread.currentThread().isInterrupted()) {
                throw new LifecycleException.LifecycleInterruptedException("Execution was interrupted!");
            }

            try {
                task.executeTask(conversationMemory);
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
