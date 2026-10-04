/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IDescribedTask;
import ai.labs.eddi.engine.runtime.IDiscardableTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The per-conversation FIFO shared by both coordinators — extracted verbatim
 * from {@link InMemoryConversationCoordinator}: the queue map, the CAS loop,
 * the C10 rollback, the {@code submitNext} drain, the {@link IDiscardableTask}
 * notification, the capacity limit, the dead-letter ring and the metrics.
 * <p>
 * The only point of variation is {@link #execute}: the in-memory coordinator
 * hands the head task straight to the runtime (synchronously, so a rejected
 * submission still throws to the caller), the cluster coordinator first
 * acquires the conversation's cluster lease asynchronously.
 *
 * <h3>Failure handling</h3>
 * <ul>
 * <li><b>No retry after execution starts</b>: a task that reports failure has
 * already run — possibly calling an LLM, executing tools and spending money. It
 * is dead-lettered once, never re-executed.</li>
 * <li><b>Submission rejection rolls back</b>: if handing the task to the
 * runtime throws, the task is taken back off the queue (and the map entry
 * dropped when it was the head), so a rejected submission cannot wedge the
 * conversation.</li>
 * <li><b>A dropped task is told it was dropped</b>: when the rejection happens
 * while scheduling the NEXT queued task there is no caller left to roll back,
 * so the task is dead-lettered. A task implementing {@link IDiscardableTask}
 * gets its {@code onDiscarded} hook invoked in that case — without it the
 * turn's own cleanup (releasing the in-flight metrics reference, completing the
 * caller's response handler) would never run, because its body is never
 * invoked.</li>
 * </ul>
 *
 * @author ginccc
 * @see ai.labs.eddi.engine.runtime.IEventBus
 */
public abstract class AbstractQueuedConversationCoordinator implements IConversationCoordinator {

    protected final Map<String, BlockingQueue<Callable<Void>>> conversationQueues = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<DeadLetterEntry> deadLetters = new ConcurrentLinkedDeque<>();
    /** Serializes dead-letter add+trim so the cap is enforced deterministically. */
    private final Object deadLetterLock = new Object();
    protected final AtomicLong totalProcessed = new AtomicLong(0);
    protected final AtomicLong totalDeadLettered = new AtomicLong(0);
    private final AtomicLong deadLetterIdCounter = new AtomicLong(0);

    protected final IRuntime runtime;
    protected final MeterRegistry meterRegistry;
    private final int maxActiveConversations;

    /**
     * Upper bound on retained dead-letter entries. The active-conversation map is
     * already capped; without this bound a storm of permanently-failing
     * conversations would grow {@link #deadLetters} without limit. Oldest entries
     * are evicted first (the dashboard inspects the most recent failures). Set to
     * {@code -1} to disable the cap (unbounded).
     */
    private final int maxDeadLetters;

    private static final Logger log = Logger.getLogger(AbstractQueuedConversationCoordinator.class);

    protected AbstractQueuedConversationCoordinator(IRuntime runtime, MeterRegistry meterRegistry, int maxActiveConversations,
            int maxDeadLetters) {
        if (maxDeadLetters < -1) {
            throw new IllegalArgumentException(
                    "eddi.coordinator.max-dead-letters must be >= -1 (-1 = unbounded, 0 = retain none), got " + maxDeadLetters);
        }
        this.runtime = runtime;
        this.meterRegistry = meterRegistry;
        this.maxActiveConversations = maxActiveConversations;
        this.maxDeadLetters = maxDeadLetters;
    }

    @PostConstruct
    protected void initMetrics() {
        meterRegistry.gauge("eddi.coordinator.active_conversations", conversationQueues, Map::size);
        meterRegistry.gauge("eddi.coordinator.queue_depth", conversationQueues, this::computeTotalQueueDepth);
        FunctionCounter.builder("eddi.coordinator.total_processed", totalProcessed, AtomicLong::doubleValue)
                .description("Total conversation tasks processed")
                .register(meterRegistry);
        // Aligned across both coordinators: what was dead-lettered, and how many
        // dead letters are retained right now.
        FunctionCounter.builder("eddi.coordinator.total_dead_lettered", totalDeadLettered, AtomicLong::doubleValue)
                .description("Conversation tasks dead-lettered (failed after they started, or could not be scheduled)")
                .register(meterRegistry);
        meterRegistry.gauge("eddi.coordinator.dead_letters", this, AbstractQueuedConversationCoordinator::retainedDeadLetterCount);
    }

    /**
     * Dead letters currently retained — the in-memory ring's size; the cluster
     * coordinator reports its stream's message count.
     */
    protected double retainedDeadLetterCount() {
        return deadLetters.size();
    }

    private double computeTotalQueueDepth(Map<String, BlockingQueue<Callable<Void>>> queues) {
        return queues.values().stream().mapToInt(BlockingQueue::size).sum();
    }

    @Override
    public void submitInOrder(String conversationId, Callable<Void> callable) {
        final String safeConversationId = sanitize(conversationId);
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
                    try {
                        execute(conversationId, queue, callable);
                    } catch (RuntimeException | Error e) {
                        // C10: the submission failed, so NOTHING is scheduled to run
                        // the head of this queue — and submitNext() only ever runs
                        // from a completion callback. Leaving the callable queued
                        // would wedge this conversation permanently (every later turn
                        // sees a non-empty queue and just waits) and leak the map
                        // entry for the JVM's lifetime. Undo the enqueue and drop the
                        // now-empty queue so the next turn starts a fresh one.
                        //
                        // We still hold the queue monitor, so nothing can have been
                        // offered in between: our callable is the only element.
                        queue.remove(callable);
                        if (queue.isEmpty()) {
                            conversationQueues.remove(conversationId, queue);
                        }
                        log.warnf("Submission failed for conversationId=%s — rolled the task back off the queue "
                                + "so the conversation stays usable", safeConversationId);
                        throw e;
                    }
                }
                return; // success
            }
        }
    }

    /**
     * Hands a task to the runtime. Throws (synchronously) if the SUBMISSION itself
     * is rejected — the only genuinely pre-execution failure mode; callers must
     * un-queue the task in that case (C10).
     * <p>
     * C13: there is deliberately NO retry on {@code onFailure}. That callback is
     * only ever raised from INSIDE the executor task, i.e. after the turn has
     * already started running — it may have called an LLM, executed tools, written
     * memory and spent money. Re-running the very same callable repeats all of it.
     * A failed turn is dead-lettered once and the queue moves on.
     */
    protected void execute(String conversationId, BlockingQueue<Callable<Void>> queue, Callable<Void> callable) {
        runtime.submitCallable(callable, new IRuntime.IFinishedExecution<>() {
            @Override
            public void onComplete(Void result) {
                totalProcessed.incrementAndGet();
                submitNext(conversationId, queue);
            }

            @Override
            public void onFailure(Throwable t) {
                log.errorf(t, "In-memory task failed after it had already started (conversationId=%s) — dead-lettering "
                        + "without retry; re-running it would repeat any side effects it already performed",
                        sanitize(conversationId));
                routeToDeadLetter(conversationId, t, callable);
                totalProcessed.incrementAndGet();
                submitNext(conversationId, queue);
            }
        }, null);
    }

    /**
     * Records a dead letter. {@code task} is the dropped or failed task — a cluster
     * coordinator captures its turn descriptor; the in-memory ring keeps the
     * payload shape it always had.
     */
    protected void routeToDeadLetter(String conversationId, Throwable failure, Callable<Void> task) {
        recordLocalDeadLetter(conversationId, failure, task);
    }

    /**
     * The in-memory ring buffer; also the cluster coordinator's degraded fallback.
     */
    protected final void recordLocalDeadLetter(String conversationId, Throwable failure, Callable<Void> task) {
        String id = nextLocalDeadLetterId(deadLetterIdCounter.incrementAndGet());
        String error = failure.getMessage() != null ? failure.getMessage() : "unknown";
        long timestamp = System.currentTimeMillis();
        String payload = String.format("{\"conversationId\":\"%s\",\"error\":\"%s\",\"timestamp\":%d}", conversationId, error.replace("\"", "\\\""),
                timestamp);

        totalDeadLettered.incrementAndGet();

        // Serialize add+trim so concurrent failures enforce the cap deterministically
        // (without the lock, parallel trims could transiently leave the deque a few
        // entries below the cap). pollFirst() evicts the oldest; the just-added entry
        // is at the tail, so the newest failures are always retained (for cap > 0).
        // size() on a ConcurrentLinkedDeque is O(n), so the excess is computed once.
        Map<String, Object> turn = describe(task);
        DeadLetterClassifier.Classification classification = DeadLetterClassifier.classify(failure, turn);
        synchronized (deadLetterLock) {
            deadLetters.addLast(new DeadLetterEntry(id, conversationId, error, timestamp, payload, turn,
                    classification.reason(), localNodeId(), classification.fence()));
            if (maxDeadLetters >= 0) {
                for (int excess = deadLetters.size() - maxDeadLetters; excess > 0; excess--) {
                    if (deadLetters.pollFirst() == null) {
                        break;
                    }
                }
            }
        }
    }

    /** The node recorded on a ring-buffer entry; {@code null} on a single node. */
    protected String localNodeId() {
        return null;
    }

    /** How many entries the node-local ring holds. */
    public int localDeadLetterCount() {
        return deadLetters.size();
    }

    /**
     * The id of a ring-buffer entry; the cluster coordinator marks its fallback
     * entries.
     */
    protected String nextLocalDeadLetterId(long counter) {
        return String.valueOf(counter);
    }

    /**
     * The replayable description of a task, or {@code null} when it carries none.
     */
    protected static Map<String, Object> describe(Callable<Void> task) {
        if (task instanceof IDescribedTask described) {
            try {
                return described.describe();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    protected void submitNext(String conversationId, BlockingQueue<Callable<Void>> queue) {
        // Collected under the queue monitor, notified after releasing it: the hook
        // completes an HTTP response handler and must not run while a
        // per-conversation lock is held.
        List<DiscardedTask> discarded = List.of();
        try {
            synchronized (queue) {
                if (queue.isEmpty()) {
                    return;
                }
                queue.remove(); // drop the task that just finished

                while (!queue.isEmpty()) {
                    Callable<Void> next = queue.element();
                    try {
                        execute(conversationId, queue, next);
                        return;
                    } catch (RuntimeException | Error e) {
                        // C10 (submitNext side): there is no caller to propagate to here —
                        // this runs from a completion callback. Dropping out would leave
                        // the queue non-empty with nothing scheduled to drain it, wedging
                        // the conversation forever. Dead-letter the task we could not
                        // schedule and try the next one.
                        log.errorf(e, "Failed to schedule the next queued task (conversationId=%s) — dead-lettering it "
                                + "so the conversation queue keeps draining", sanitize(conversationId));
                        routeToDeadLetter(conversationId, e, next);
                        totalProcessed.incrementAndGet();
                        queue.remove();
                        // The callable is gone WITHOUT having been invoked, so its own
                        // finally-block cleanup (releasing the in-flight metrics
                        // reference, completing the caller's response handler) never
                        // runs. Tell the task so it can do that itself — otherwise
                        // dropping it here re-creates exactly the leak the release
                        // block exists to prevent, and the HTTP caller waits forever.
                        if (next instanceof IDiscardableTask discardable) {
                            if (discarded.isEmpty()) {
                                discarded = new ArrayList<>(1);
                            }
                            discarded.add(new DiscardedTask(discardable, e));
                        }
                    }
                }

                // Eager cleanup: remove empty queue to prevent memory leaks.
                // Uses remove(key, value) to avoid removing a new queue that was
                // just created by a concurrent submitInOrder call.
                conversationQueues.remove(conversationId, queue);
            }
        } finally {
            for (DiscardedTask task : discarded) {
                notifyDiscarded(conversationId, task);
            }
        }
    }

    /** A queued task that was dropped before it ever ran, plus the reason. */
    protected record DiscardedTask(IDiscardableTask task, Throwable cause) {
    }

    protected void notifyDiscarded(String conversationId, DiscardedTask discarded) {
        try {
            discarded.task().onDiscarded(discarded.cause());
        } catch (RuntimeException | Error hookFailure) {
            // The hook is best effort — a failing one must never break the drain
            // of the remaining queue.
            log.errorf(hookFailure, "Discard hook failed for conversationId=%s", sanitize(conversationId));
        }
    }

    // ==================== Status Methods ====================

    /**
     * Number of conversations with a live queue entry in the map — INCLUDING
     * entries whose queue is currently empty, which {@link #getQueueDepths()}
     * deliberately filters out.
     * <p>
     * This is the leak-visible count: an orphaned empty queue never shows up in
     * {@code getQueueDepths()}, so only this accessor can tell "the map entry was
     * cleaned up" from "the map entry leaked". Package-private for the tests that
     * pin the C10 cleanup; the gauge {@code eddi.coordinator.active_conversations}
     * reports the same number.
     */
    int activeConversationCount() {
        return conversationQueues.size();
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
    public Optional<DeadLetterEntry> getDeadLetter(String entryId) {
        for (DeadLetterEntry entry : deadLetters) {
            if (entry.id().equals(entryId)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /**
     * Removes an entry whose replay has been submitted. The failed task object is
     * gone, so nothing is re-injected here: the replay itself is a NEW turn built
     * from the entry's captured input, which {@code RestCoordinatorAdmin} submits
     * through the conversation service BEFORE calling this — and refuses (409,
     * entry kept) when the entry carries no replayable input.
     */
    @Override
    public boolean replayDeadLetter(String entryId) {
        Iterator<DeadLetterEntry> it = deadLetters.iterator();
        while (it.hasNext()) {
            DeadLetterEntry entry = it.next();
            if (entry.id().equals(entryId)) {
                it.remove();
                log.infof("Dead-letter %s for conversation %s removed after its replay was submitted", sanitize(entryId),
                        sanitize(entry.conversationId()));
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
                log.infof("Discarded dead-letter %s for conversation %s", sanitize(entryId), sanitize(entry.conversationId()));
                return true;
            }
        }
        return false;
    }

    @Override
    public int purgeDeadLetters(String conversationId) {
        int removed = 0;
        Iterator<DeadLetterEntry> it = deadLetters.iterator();
        while (it.hasNext()) {
            if (Objects.equals(conversationId, it.next().conversationId())) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    @Override
    public int purgeDeadLetters() {
        int count = deadLetters.size();
        deadLetters.clear();
        log.infof("Purged %d dead-letter entries (in-memory)", count);
        return count;
    }
}
