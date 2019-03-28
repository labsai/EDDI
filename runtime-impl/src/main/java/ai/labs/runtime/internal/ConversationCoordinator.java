package ai.labs.runtime.internal;

import ai.labs.runtime.IConversationCoordinator;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedTransferQueue;

import static ai.labs.runtime.SystemRuntime.IRuntime;

@Singleton
@Slf4j
public class ConversationCoordinator implements IConversationCoordinator {
    private final Map<String, BlockingQueue<Callable<Void>>> conversationQueues = new ConcurrentHashMap<>();
    private final IRuntime runtime;

    @Inject
    public ConversationCoordinator(IRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void submitInOrder(String conversationId, Callable<Void> callable) {
        final BlockingQueue<Callable<Void>> queue = conversationQueues.
                computeIfAbsent(conversationId, (key) -> new LinkedTransferQueue<>());

        if (queue.isEmpty()) {
            queue.offer(callable);
            runtime.submitCallable(callable,
                    new IRuntime.IFinishedExecution<>() {
                        @Override
                        public void onComplete(Void result) {
                            submitNext();
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            log.error(t.getLocalizedMessage(), t);
                            submitNext();
                        }

                        private void submitNext() {
                            synchronized (queue) {
                                queue.remove();
                                if (!queue.isEmpty()) {
                                    runtime.submitCallable(queue.element(), this, null);
                                }
                            }
                        }
                    }, Collections.emptyMap());
        } else {
            queue.offer(callable);
        }
    }
}
