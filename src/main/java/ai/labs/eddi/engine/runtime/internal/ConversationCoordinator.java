package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IRuntime;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedTransferQueue;

@ApplicationScoped
public class ConversationCoordinator implements IConversationCoordinator {
    private final Map<String, BlockingQueue<Callable<Void>>> conversationQueues = new ConcurrentHashMap<>();
    private final IRuntime runtime;

    private static final Logger log = Logger.getLogger(ConversationCoordinator.class);

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
                                if (!queue.isEmpty()) {
                                    queue.remove();

                                    if (!queue.isEmpty()) {
                                        runtime.submitCallable(queue.element(), this, null);
                                    }
                                }
                            }
                        }
                    }, Collections.emptyMap());
        } else {
            queue.offer(callable);
        }
    }
}
