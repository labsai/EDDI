package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IRuntime;
import io.quarkus.arc.DefaultBean;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedTransferQueue;

/**
 * In-memory implementation of {@link IConversationCoordinator}.
 *
 * <p>This is the default event bus — uses in-process queues with no external dependencies.
 * Suitable for single-instance deployments. For horizontal scaling, use
 * {@code NatsConversationCoordinator} by setting {@code eddi.messaging.type=nats}.</p>
 *
 * <p>Ensures that messages within the same conversation are processed sequentially,
 * while different conversations can be processed concurrently by the thread pool.</p>
 *
 * @author ginccc
 * @see ai.labs.eddi.engine.runtime.IEventBus
 */
@ApplicationScoped
@DefaultBean
public class InMemoryConversationCoordinator implements IConversationCoordinator {

    private final Map<String, BlockingQueue<Callable<Void>>> conversationQueues = new ConcurrentHashMap<>();

    private final IRuntime runtime;

    private static final Logger log = Logger.getLogger(InMemoryConversationCoordinator.class);

    @Inject
    public InMemoryConversationCoordinator(IRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Submits a message processing task to be executed in order for the given conversation.
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Get or create a queue for this conversation (thread-safe via ConcurrentHashMap)</li>
     *   <li>If queue is empty: add task, submit to runtime immediately, chain to next on complete</li>
     *   <li>If queue is not empty: add task, it will be submitted when its turn comes</li>
     * </ol>
     *
     * @param conversationId the unique identifier of the conversation
     * @param callable the task to execute (typically lifecycle pipeline execution)
     */
    @Override
    public void submitInOrder(String conversationId, Callable<Void> callable) {
        final BlockingQueue<Callable<Void>> queue = conversationQueues.
                computeIfAbsent(conversationId, (key) -> new LinkedTransferQueue<>());

        synchronized (queue) {
            boolean wasEmpty = queue.isEmpty();
            queue.offer(callable);

            if (wasEmpty) {
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
                        }, null);
            }
        }
    }
}
