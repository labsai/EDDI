package ai.labs.runtime;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * @author ginccc
 */
public interface IConversationCoordinator {
    Future<?> submitInOrder(String conversationId, Callable<?> callable);
}
