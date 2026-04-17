package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.DefaultBean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory implementation of {@link IConversationCoordinator}.
 *
 * <p>
 * This is the default event bus — uses in-process queues with no external
 * dependencies. Suitable for single-instance deployments. For horizontal
 * scaling, use {@code NatsConversationCoordinator} by setting
 * {@code eddi.messaging.type=nats}.
 * </p>
 *
 * <p>
 * Ensures that messages within the same conversation are processed
 * sequentially, while different conversations can be processed concurrently by
 * the thread pool.
 * </p>
 *
 * <p>
 * Tracks dead-lettered tasks in memory for inspection via the Coordinator
 * Dashboard.
 * </p>
 *
 * <h3>Hardening (v6.0.2)</h3>
 * <ul>
 * <li><b>Eager cleanup</b>: Queues are removed from the map as soon as they
 * become empty, preventing memory leaks from abandoned conversations.</li>
 * <li><b>Max-size limit</b>: Configurable cap on active conversations
 * ({@code eddi.coordinator.max-active-conversations}). Follow-up messages to
 * currently-queued conversations are always accepted; conversations that have
 * fully drained are treated as new.</li>
 * <li><b>Micrometer metrics</b>: {@code eddi.coordinator.active_conversations},
 * {@code eddi.coordinator.queue_depth},
 * {@code eddi.coordinator.total_processed}.</li>
 * </ul>
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
    private final MeterRegistry meterRegistry;
    private final int maxActiveConversations;

    private static final Logger log = Logger.getLogger(InMemoryConversationCoordinator.class);

    @Inject
    public InMemoryConversationCoordinator(IRuntime runtime, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.coordinator.max-active-conversations", defaultValue = "10000") int maxActiveConversations) {
        this.runtime = runtime;
        this.meterRegistry = meterRegistry;
        this.maxActiveConversations = maxActiveConversations;
    }

    @PostConstruct
    void initMetrics() {
        meterRegistry.gauge("eddi.coordinator.active_conversations", conversationQueues, Map::size);
        meterRegistry.gauge("eddi.coordinator.queue_depth", conversationQueues, this::computeTotalQueueDepth);
        FunctionCounter.builder("eddi.coordinator.total_processed", totalProcessed, AtomicLong::doubleValue)
                .description("Total conversation tasks processed")
                .register(meterRegistry);
    }

    private double computeTotalQueueDepth(Map<String, BlockingQueue<Callable<Void>>> queues) {
        return queues.values().stream().mapToInt(BlockingQueue::size).sum();
    }

    private static String sanitizeForLog(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace('\n', '_').replace('\r', '_');
    }

    @Override
    public void submitInOrder(String conversationId, Callable<Void> callable) {
        final String safeConversationId = sanitizeForLog(conversationId);
        // Max-size check: only reject truly new conversations, not follow-up messages.
        // Note: this check is intentionally non-atomic (soft limit). Two threads
        // could both pass the check and briefly exceed maxActiveConversations.
        // This is acceptable for a soft backpressure limit.
        if (!conversationQueues.containsKey(conversationId) && conversationQueues.size() >= maxActiveConversations) {
            log.warnf("Coordinator capacity exceeded (%d active conversations). Rejecting new conversationId=%s", maxActiveConversations,
                    safeConversationId);
            throw new RejectedExecutionException(
                    "Coordinator capacity exceeded: " + maxActiveConversations + " active conversations. Try again later.");
        }

        // CAS loop: after acquiring the lock on the queue, verify it's still the
        // map's current value. If submitNext() removed it (eager cleanup) between
        // our computeIfAbsent and our synchronized(queue), the queue is orphaned
        // and we must retry with a fresh computeIfAbsent to avoid two queues for
        // the same conversation running in parallel.
        //
        // Happens-before correctness: ConcurrentHashMap.get() is ordered after
        // the monitor release in submitNext's synchronized(queue) block, so our
        // identity check sees the removal. In practice, retries are 0–1.
        for (int attempt = 0;; attempt++) {
            final BlockingQueue<Callable<Void>> queue = conversationQueues.computeIfAbsent(conversationId, (key) -> new LinkedTransferQueue<>());

            synchronized (queue) {
                // Verify this queue is still the current value in the map.
                // If not, another thread cleaned it up — retry.
                if (conversationQueues.get(conversationId) != queue) {
                    if (attempt >= 3) {
                        log.debugf("CAS loop retried %d times for conversationId=%s (expected 0-1)", attempt, safeConversationId);
                    }
                    continue; // retry with fresh computeIfAbsent
                }

                boolean wasEmpty = queue.isEmpty();
                boolean enqueued = queue.offer(callable);
                if (!enqueued) {
                    log.warnf("Failed to enqueue task for conversationId=%s", safeConversationId);
                    throw new RejectedExecutionException("Failed to enqueue task for conversationId=" + safeConversationId);
                }

                if (wasEmpty) {
                    executeWithRetry(conversationId, queue, callable, 0);
                }
                return; // success
            }
        }
    }

    private void executeWithRetry(String conversationId, BlockingQueue<Callable<Void>> queue, Callable<Void> callable, int attempt) {
        runtime.submitCallable(callable, new IRuntime.IFinishedExecution<>() {
            @Override
            public void onComplete(Void result) {
                totalProcessed.incrementAndGet();
                submitNext(conversationId, queue);
            }

            @Override
            public void onFailure(Throwable t) {
                int nextAttempt = attempt + 1;
                if (nextAttempt < MAX_RETRIES) {
                    log.warnf(t, "In-memory task failed (conversationId=%s, attempt=%d/%d), retrying...", conversationId, nextAttempt, MAX_RETRIES);
                    executeWithRetry(conversationId, queue, callable, nextAttempt);
                } else {
                    log.errorf(t, "In-memory task exhausted retries (conversationId=%s, attempts=%d), dead-lettering", conversationId, nextAttempt);
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
        String payload = String.format("{\"conversationId\":\"%s\",\"error\":\"%s\",\"timestamp\":%d}", conversationId, error.replace("\"", "\\\""),
                timestamp);

        deadLetters.addLast(new DeadLetterEntry(id, conversationId, error, timestamp, payload));
        totalDeadLettered.incrementAndGet();
    }

    private void submitNext(String conversationId, BlockingQueue<Callable<Void>> queue) {
        synchronized (queue) {
            if (!queue.isEmpty()) {
                queue.remove();

                if (!queue.isEmpty()) {
                    executeWithRetry(conversationId, queue, queue.element(), 0);
                } else {
                    // Eager cleanup: remove empty queue to prevent memory leaks.
                    // Uses remove(key, value) to avoid removing a new queue that was
                    // just created by a concurrent submitInOrder call.
                    conversationQueues.remove(conversationId, queue);
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
                log.infof("Replayed dead-letter %s for conversation %s (in-memory — task reference lost, removed from DL queue)", entryId,
                        entry.conversationId());
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
