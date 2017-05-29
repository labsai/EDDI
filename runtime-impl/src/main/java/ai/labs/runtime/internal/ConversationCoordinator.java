package ai.labs.runtime.internal;

import ai.labs.runtime.IConversationCoordinator;
import ai.labs.runtime.SystemRuntime;

import javax.inject.Singleton;
import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;

@Singleton
public class ConversationCoordinator implements IConversationCoordinator {
    private final Map<String, Queue<FutureTask<?>>> conversationQueues = new ConcurrentHashMap<>();

    @Override
    public Future<?> submitInOrder(String conversationId, Callable<?> callable) {
        final Queue<FutureTask<?>> queue = conversationQueues.
                computeIfAbsent(conversationId, (key) -> new LinkedTransferQueue<>());

        FutureTask<?> futureTask = new FutureTask<>(callable);
        synchronized (queue) {
            if (queue.isEmpty()) {
                queue.offer(futureTask);
                SystemRuntime.getRuntime().submitRunable(futureTask, new SystemRuntime.IRuntime.IFinishedExecution<Object>() {
                    @Override
                    public void onComplete(Object result) {
                        submitNext();
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        submitNext();
                    }

                    private void submitNext() {
                        synchronized (queue) {
                            queue.remove();
                            if (!queue.isEmpty()) {
                                SystemRuntime.getRuntime().submitRunable(queue.element(), this, null);
                            }
                        }
                    }
                }, Collections.emptyMap());
            } else {
                queue.offer(futureTask);
            }
        }
        return futureTask;
    }
}
