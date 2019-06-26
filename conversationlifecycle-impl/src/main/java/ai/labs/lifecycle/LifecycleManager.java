package ai.labs.lifecycle;

import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.utilities.RuntimeUtilities;

import java.util.LinkedList;
import java.util.List;

public class LifecycleManager implements ILifecycleManager {
    private static final String KEY_ACTIONS = "actions";
    private List<ILifecycleTask> lifecycleTasks;

    public LifecycleManager() {
        lifecycleTasks = new LinkedList<>();
    }

    public void executeLifecycle(final IConversationMemory conversationMemory) throws LifecycleException, ConversationStopException {
        RuntimeUtilities.checkNotNull(conversationMemory, "conversationMemory");

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
        RuntimeUtilities.checkNotNull(lifecycleTask, "lifecycleTask");
        lifecycleTasks.add(lifecycleTask);
    }
}
