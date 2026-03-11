package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IRuntime;
import io.quarkus.arc.DefaultBean;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p>Tracks dead-lettered tasks in memory for inspection via the Coordinator Dashboard.</p>
 *
 * @author ginccc
 * @see ai.labs.eddi.engine.runtime.IEventBus
 */
@ApplicationScoped
@DefaultBean
public class InMemoryConversationCoordinator implements IConversationCoordinator {

    private static final int MAX_RETRIES = 3;

    private final Map<String, BlockingQueue<Callable<Void>>> conversationQueues = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<DeadLetterEntry> deadLetters = new ConcurrentLinkedDeque<>();
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalDeadLettered = new AtomicLong(0);
    private final AtomicLong deadLetterIdCounter = new AtomicLong(0);

    private final IRuntime runtime;

    private static final Logger log = Logger.getLogger(InMemoryConversationCoordinator.class);

    @Inject
    public InMemoryConversationCoordinator(IRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void submitInOrder(String conversationId, Callable<Void> callable) {
        final BlockingQueue<Callable<Void>> queue = conversationQueues.
                computeIfAbsent(conversationId, (key) -> new LinkedTransferQueue<>());

        synchronized (queue) {
            boolean wasEmpty = queue.isEmpty();
            queue.offer(callable);

            if (wasEmpty) {
                executeWithRetry(conversationId, queue, callable, 0);
            }
        }
    }

    private void executeWithRetry(String conversationId, BlockingQueue<Callable<Void>> queue,
                                   Callable<Void> callable, int attempt) {
        runtime.submitCallable(callable,
                new IRuntime.IFinishedExecution<>() {
                    @Override
                    public void onComplete(Void result) {
                        totalProcessed.incrementAndGet();
                        submitNext(conversationId, queue);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        int nextAttempt = attempt + 1;
                        if (nextAttempt < MAX_RETRIES) {
                            log.warnf(t, "In-memory task failed (conversationId=%s, attempt=%d/%d), retrying...",
                                    conversationId, nextAttempt, MAX_RETRIES);
                            executeWithRetry(conversationId, queue, callable, nextAttempt);
                        } else {
                            log.errorf(t, "In-memory task exhausted retries (conversationId=%s, attempts=%d), dead-lettering",
                                    conversationId, nextAttempt);
                            routeToDeadLetter(conversationId, t);
                            totalProcessed.incrementAndGet();
                            submitNext(conversationId, queue);
                        }
                    }
                }, null);
    }

    private void routeToDeadLetter(String conversationId, Throwable failure) {
        String id = String.valueOf(deadLetterIdCounter.incrementAndGet());
        String error = failure.getMessage() != null ? failure.getMessage() : "unknown";
        long timestamp = System.currentTimeMillis();
        String payload = String.format(
                "{\"conversationId\":\"%s\",\"error\":\"%s\",\"timestamp\":%d}",
                conversationId, error.replace("\"", "\\\""), timestamp);

        deadLetters.addLast(new DeadLetterEntry(id, conversationId, error, timestamp, payload));
        totalDeadLettered.incrementAndGet();
    }

    private void submitNext(String conversationId, BlockingQueue<Callable<Void>> queue) {
        synchronized (queue) {
            if (!queue.isEmpty()) {
                queue.remove();

                if (!queue.isEmpty()) {
                    executeWithRetry(conversationId, queue, queue.element(), 0);
                }
            }
        }
    }

    // ==================== Status Methods ====================

    @Override
    public String getCoordinatorType() {
        return "in-memory";
    }

    @Override
    public Map<String, Integer> getQueueDepths() {
        Map<String, Integer> depths = new LinkedHashMap<>();
        conversationQueues.forEach((id, q) -> {
            int size = q.size();
            if (size > 0) {
                depths.put(id, size);
            }
        });
        return depths;
    }

    @Override
    public long getTotalProcessed() {
        return totalProcessed.get();
    }

    @Override
    public long getTotalDeadLettered() {
        return totalDeadLettered.get();
    }

    // ==================== Dead-Letter Methods ====================

    @Override
    public List<DeadLetterEntry> getDeadLetters() {
        return new ArrayList<>(deadLetters);
    }

    @Override
    public boolean replayDeadLetter(String entryId) {
        Iterator<DeadLetterEntry> it = deadLetters.iterator();
        while (it.hasNext()) {
            DeadLetterEntry entry = it.next();
            if (entry.id().equals(entryId)) {
                it.remove();
                log.infof("Replayed dead-letter %s for conversation %s (in-memory — task reference lost, removed from DL queue)",
                        entryId, entry.conversationId());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean discardDeadLetter(String entryId) {
        Iterator<DeadLetterEntry> it = deadLetters.iterator();
        while (it.hasNext()) {
            DeadLetterEntry entry = it.next();
            if (entry.id().equals(entryId)) {
                it.remove();
                log.infof("Discarded dead-letter %s for conversation %s", entryId, entry.conversationId());
                return true;
            }
        }
        return false;
    }

    @Override
    public int purgeDeadLetters() {
        int count = deadLetters.size();
        deadLetters.clear();
        log.infof("Purged %d dead-letter entries (in-memory)", count);
        return count;
    }
}
