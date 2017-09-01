package ai.labs.runtime;

import java.util.concurrent.Callable;

/**
 * @author ginccc
 */
public interface IConversationCoordinator {
    void submitInOrder(String conversationId, Callable<Void> callable);
}
