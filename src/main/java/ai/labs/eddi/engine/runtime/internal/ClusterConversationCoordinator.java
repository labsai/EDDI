/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.cluster.ClusterPresence;
import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.IDeadLetterStore;
import ai.labs.eddi.engine.cluster.JetStreamDeadLetterStore;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import ai.labs.eddi.engine.cluster.lease.NatsLeaseManager;
import ai.labs.eddi.engine.model.CoordinatorStatus;
import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.IDiscardableTask;
import ai.labs.eddi.engine.runtime.ILeaseAwareTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Singleton;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The cluster-mode coordinator ({@code eddi.messaging.type=nats}).
 * <p>
 * Same per-conversation FIFO as the in-memory coordinator (it shares
 * {@link AbstractQueuedConversationCoordinator}), with one addition: before the
 * head task of a conversation runs, this node acquires the conversation's
 * <b>cluster lease</b>. The turn still runs on the node that received the
 * request — SSE, the caller's identity, response handlers, live group
 * discussions and HITL all keep working unchanged — but turns of one
 * conversation never overlap anywhere in the cluster, and each one runs on top
 * of every turn committed before it (the turn reloads a superseded snapshot,
 * and its writes carry the lease's fencing token).
 * <p>
 * <b>No NATS I/O under the queue monitor.</b> {@link #execute} only registers
 * an asynchronous acquire; the lease completes on another thread, which then
 * hands the task to the runtime. Under contention the turn waits for the lease
 * without occupying a pool thread. The old NATS coordinator published to
 * JetStream while holding the per-conversation lock, which stacked 10/20/30 s
 * delays when NATS was slow.
 * <p>
 * <b>Acquire timeout</b> ({@code eddi.cluster.lease.acquire-timeout}, 45 s):
 * the task is discarded (its {@code onDiscarded} answers the caller 409 with
 * {@code Retry-After}); it is NOT dead-lettered, because the input was never
 * consumed.
 * <p>
 * <b>Dead letters</b> go to a JetStream stream every node reads, with the
 * turn's input captured so an admin can replay it; while NATS is down they fall
 * back to the node-local ring (ids prefixed {@code local-}).
 */
@Singleton
@Typed(ClusterConversationCoordinator.class)
public class ClusterConversationCoordinator extends AbstractQueuedConversationCoordinator {

    private static final Logger LOGGER = Logger.getLogger(ClusterConversationCoordinator.class);
    private static final long DEAD_LETTER_COUNT_CACHE_MILLIS = 5_000;

    private final IConversationLeaseManager leases;
    private final IDeadLetterStore deadLetterStore;
    private final NatsConnectionManager connections;
    private final ClusterPresence presence;
    private final Duration acquireTimeout;
    private final ExecutorService continuations = Executors.newVirtualThreadPerTaskExecutor();
    private volatile long cachedDeadLetterCount;
    private volatile long deadLetterCountAt;

    @Inject
    public ClusterConversationCoordinator(IRuntime runtime, MeterRegistry meterRegistry, NatsLeaseManager leases,
            JetStreamDeadLetterStore deadLetterStore, NatsConnectionManager connections, ClusterPresence presence,
            @ConfigProperty(name = "eddi.coordinator.max-active-conversations", defaultValue = "10000") int maxActiveConversations,
            @ConfigProperty(name = "eddi.coordinator.max-dead-letters", defaultValue = "1000") int maxDeadLetters) {
        this(runtime, meterRegistry, leases, deadLetterStore, connections, presence, maxActiveConversations, maxDeadLetters,
                connections.config().leaseAcquireTimeout());
        presence.contribute(this::presenceNumbers);
        connections.onConnected(this::forwardLocalDeadLetters);
    }

    /** For tests and the in-JVM cluster ITs. */
    public ClusterConversationCoordinator(IRuntime runtime, MeterRegistry meterRegistry, IConversationLeaseManager leases,
            IDeadLetterStore deadLetterStore, NatsConnectionManager connections, ClusterPresence presence, int maxActiveConversations,
            int maxDeadLetters, Duration acquireTimeout) {
        super(runtime, meterRegistry, maxActiveConversations, maxDeadLetters);
        this.leases = leases;
        this.deadLetterStore = deadLetterStore;
        this.connections = connections;
        this.presence = presence;
        this.acquireTimeout = acquireTimeout;
    }

    private Map<String, Object> presenceNumbers() {
        Map<String, Object> numbers = new LinkedHashMap<>();
        numbers.put("activeConversations", activeConversationCount());
        numbers.put("leasesHeld", leases.heldCount());
        numbers.put("queueDepthTotal", getQueueDepths().values().stream().mapToInt(Integer::intValue).sum());
        return numbers;
    }

    /**
     * Acquires the lease asynchronously, then hands the task to the runtime. Called
     * under the queue monitor, so it must not do I/O — and does none: the lease
     * manager only registers a waiter and schedules the attempt.
     */
    @Override
    protected void execute(String conversationId, BlockingQueue<Callable<Void>> queue, Callable<Void> callable) {
        leases.acquire(conversationId, acquireTimeout)
                .whenCompleteAsync((lease, failure) -> {
                    if (failure != null) {
                        leaseNotObtained(conversationId, queue, callable, unwrap(failure));
                    } else {
                        runUnderLease(conversationId, queue, callable, lease);
                    }
                }, continuations);
    }

    private void runUnderLease(String conversationId, BlockingQueue<Callable<Void>> queue, Callable<Void> callable, LeaseHandle lease) {
        try {
            // Inside the guard: a bind that throws must still release the lease, answer the
            // caller and drain the queue — outside it, the conversation stayed wedged.
            if (callable instanceof ILeaseAwareTask aware) {
                aware.bindLease(lease);
            }
            runtime.submitCallable(callable, new IRuntime.IFinishedExecution<>() {
                @Override
                public void onComplete(Void result) {
                    leases.release(lease);
                    totalProcessed.incrementAndGet();
                    submitNext(conversationId, queue);
                }

                @Override
                public void onFailure(Throwable t) {
                    leases.release(lease);
                    LOGGER.errorf(t, "Clustered task failed after it had already started (conversationId=%s) — dead-lettering "
                            + "without retry; re-running it would repeat any side effects it already performed",
                            sanitize(conversationId));
                    routeToDeadLetter(conversationId, t, callable);
                    totalProcessed.incrementAndGet();
                    submitNext(conversationId, queue);
                }
            }, null);
        } catch (RuntimeException | Error e) {
            // The runtime refused the task after we obtained the lease. There is no
            // caller left to roll back to: dead-letter it, tell it it was dropped, and
            // keep draining the queue — the same contract as submitNext's rejection path.
            leases.release(lease);
            LOGGER.errorf(e, "Could not schedule the clustered task (conversationId=%s) — dead-lettering it so the queue keeps draining",
                    sanitize(conversationId));
            routeToDeadLetter(conversationId, e, callable);
            totalProcessed.incrementAndGet();
            if (callable instanceof IDiscardableTask discardable) {
                notifyDiscarded(conversationId, new DiscardedTask(discardable, e));
            }
            submitNext(conversationId, queue);
        }
    }

    /**
     * The lease did not come (another node kept it past the timeout, NATS is down
     * with {@code degraded.turns=reject}, or this node is shutting down). The task
     * never ran and its input was never consumed: discard it — its hook answers the
     * caller — and move on to the next queued task. Not a dead letter.
     */
    private void leaseNotObtained(String conversationId, BlockingQueue<Callable<Void>> queue, Callable<Void> callable, Throwable cause) {
        LOGGER.warnf("Turn of conversation %s not run: %s", sanitize(conversationId), cause.getMessage());
        if (callable instanceof IDiscardableTask discardable) {
            notifyDiscarded(conversationId, new DiscardedTask(discardable, cause));
        }
        submitNext(conversationId, queue);
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }

    // ==================== Dead letters ====================

    @Override
    protected void routeToDeadLetter(String conversationId, Throwable failure, Callable<Void> task) {
        String error = failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
        try {
            deadLetterStore.append(conversationId, error, System.currentTimeMillis(), describe(task));
            totalDeadLettered.incrementAndGet();
            deadLetterCountAt = 0;
        } catch (ClusterUnavailableException e) {
            LOGGER.warnf("Dead letter of conversation %s kept node-locally: NATS unavailable (%s)", sanitize(conversationId),
                    e.getMessage());
            recordLocalDeadLetter(conversationId, failure, task);
            scheduleForward();
        }
    }

    /** How soon a dead letter kept locally while still connected is tried again. */
    static final long FORWARD_RETRY_SECONDS = 10;

    /**
     * A dead letter can fail over to the local ring while NATS is connected, for
     * example when the stream is momentarily unavailable. Forwarding only on the
     * next reconnect would then keep it on this node indefinitely, invisible to the
     * others and lost on a restart, so it is tried again shortly as well.
     */
    private void scheduleForward() {
        ScheduledExecutorService scheduler = connections.scheduler();
        if (scheduler == null) {
            return;
        }
        try {
            scheduler.schedule(() -> {
                if (connections.isConnected()) {
                    forwardLocalDeadLetters();
                }
            }, FORWARD_RETRY_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            // the reconnect path forwards them when this node comes back
        }
    }

    /**
     * Moves the dead letters this node had to keep locally while NATS was
     * unreachable into the shared stream, so every node lists them and they survive
     * this node's restart. Runs on every (re)connect; stops at the first failure
     * and tries the rest on the next one.
     *
     * @return how many entries were forwarded
     */
    int forwardLocalDeadLetters() {
        int forwarded = 0;
        for (DeadLetterEntry local : super.getDeadLetters()) {
            try {
                deadLetterStore.append(local.conversationId(), local.error(), local.timestamp(), local.turn());
            } catch (ClusterUnavailableException e) {
                LOGGER.debugf("Forwarding local dead letters paused: %s", e.getMessage());
                break;
            }
            super.discardDeadLetter(local.id());
            forwarded++;
        }
        if (forwarded > 0) {
            deadLetterCountAt = 0;
            LOGGER.infof("Forwarded %d dead letter(s) kept on this node while NATS was unreachable to the shared stream", forwarded);
        }
        return forwarded;
    }

    @Override
    protected String nextLocalDeadLetterId(long counter) {
        return "local-" + counter;
    }

    private static boolean isLocalId(String id) {
        return id != null && id.startsWith("local-");
    }

    @Override
    protected double retainedDeadLetterCount() {
        long now = System.currentTimeMillis();
        if (now - deadLetterCountAt > DEAD_LETTER_COUNT_CACHE_MILLIS) {
            try {
                cachedDeadLetterCount = deadLetterStore.count();
                deadLetterCountAt = now;
            } catch (ClusterUnavailableException e) {
                // keep the last value
            }
        }
        return cachedDeadLetterCount + super.retainedDeadLetterCount();
    }

    @Override
    public List<DeadLetterEntry> getDeadLetters() {
        return getDeadLetters(1000, null);
    }

    /** One page of dead letters, oldest first; node-local fallback entries last. */
    public List<DeadLetterEntry> getDeadLetters(int limit, String after) {
        List<DeadLetterEntry> entries = new ArrayList<>();
        if (!isLocalId(after)) {
            try {
                entries.addAll(deadLetterStore.list(limit, after));
            } catch (ClusterUnavailableException e) {
                LOGGER.debugf("Dead-letter stream unavailable: %s", e.getMessage());
            }
        }
        // Local fallback entries come after the stream. When paging past one of them,
        // skip every local entry up to it — by number, since the entry named in `after`
        // may have been discarded or forwarded meanwhile — so a page never repeats.
        long afterLocal = isLocalId(after) ? localNumber(after) : -1;
        for (DeadLetterEntry local : super.getDeadLetters()) {
            if (entries.size() >= limit) {
                break;
            }
            if (afterLocal >= 0 && localNumber(local.id()) <= afterLocal) {
                continue;
            }
            entries.add(local);
        }
        return entries;
    }

    private static long localNumber(String id) {
        try {
            return Long.parseLong(id.substring("local-".length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public Optional<DeadLetterEntry> getDeadLetter(String entryId) {
        if (isLocalId(entryId)) {
            return super.getDeadLetter(entryId);
        }
        return deadLetterStore.get(entryId);
    }

    @Override
    public boolean replayDeadLetter(String entryId) {
        return discardDeadLetter(entryId);
    }

    @Override
    public boolean discardDeadLetter(String entryId) {
        if (isLocalId(entryId)) {
            return super.discardDeadLetter(entryId);
        }
        boolean deleted = deadLetterStore.delete(entryId);
        if (deleted) {
            deadLetterCountAt = 0;
            LOGGER.infof("Discarded dead-letter %s", sanitize(entryId));
        }
        return deleted;
    }

    @Override
    public int purgeDeadLetters() {
        int purged = super.purgeDeadLetters();
        try {
            purged += deadLetterStore.purge();
        } catch (ClusterUnavailableException e) {
            LOGGER.warnf("Dead-letter stream not purged: %s", e.getMessage());
        }
        deadLetterCountAt = 0;
        return purged;
    }

    /** Removes the dead letters of one conversation (GDPR erasure). */
    @Override
    public int purgeDeadLetters(String conversationId) {
        int purged = super.purgeDeadLetters(conversationId);
        purged += deadLetterStore.purgeConversation(conversationId);
        deadLetterCountAt = 0;
        return purged;
    }

    // ==================== Shutdown ====================

    /**
     * Turns still waiting for their lease are failed with SHUTTING_DOWN, which
     * answers their callers 409 + Retry-After at once: without it a waiter held the
     * drain until its acquire timeout and then failed after the container had gone.
     */
    @Override
    public void beginShutdown() {
        leases.stopAcquiring();
    }

    /**
     * Leases still held after the drain go back at once instead of after the TTL.
     */
    @Override
    public void completeShutdown() {
        leases.releaseAll();
    }

    // ==================== Status ====================

    @Override
    public String getCoordinatorType() {
        return "nats";
    }

    @Override
    public boolean isConnected() {
        return connections.isConnected();
    }

    @Override
    public String getConnectionStatus() {
        if (connections.isDegraded()) {
            return "DEGRADED";
        }
        return connections.status().name();
    }

    @Override
    public CoordinatorStatus getStatus() {
        Map<String, Integer> depths = getQueueDepths();
        return new CoordinatorStatus(getCoordinatorType(), isConnected(), getConnectionStatus(), depths.size(), getTotalProcessed(),
                getTotalDeadLettered(), depths, connections.node().nodeId(), clusterView());
    }

    /** The cluster section of the status: read from presence, no RPC. */
    public Map<String, Object> clusterView() {
        Map<String, Object> cluster = new LinkedHashMap<>();
        cluster.put("members", presence.members());
        cluster.put("leasesHeld", leases.heldCount());
        cluster.put("natsStatus", connections.status().name());
        cluster.put("degraded", connections.isDegraded());
        long since = connections.unavailableSinceMillis();
        cluster.put("degradedSince", connections.isDegraded() && since > 0 ? since : null);
        return cluster;
    }
}
