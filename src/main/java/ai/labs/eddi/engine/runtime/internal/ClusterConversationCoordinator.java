/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.cluster.ClusterPresence;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.ErasureTombstones;
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
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import ai.labs.eddi.engine.cluster.NodeIdentity;

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
 * <b>Handoff</b>: when a turn finishes and the next turn of the same
 * conversation is already queued on this node, the lease goes straight to it
 * (see {@link IConversationLeaseManager#handOver}) — no release, no new acquire
 * — unless another node is waiting for it.
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
    static final long LOCAL_FORWARD_SECONDS = 30;

    private final IConversationLeaseManager leases;
    private final IDeadLetterStore deadLetterStore;
    private final NatsConnectionManager connections;
    private final ClusterPresence presence;
    private final Duration acquireTimeout;
    private final ExecutorService continuations = Executors.newVirtualThreadPerTaskExecutor();
    /**
     * Leases parked for the next queued turn of a conversation while
     * {@code submitNext} hands it to {@link #execute}. Each parking is its own
     * {@link Handoff}, compared by identity: the same lease is parked again by
     * every turn it is handed to, so a turn's cleanup must only ever take back its
     * own parking, never the one the next turn has just made.
     */
    private final Map<String, Handoff> handoffs = new ConcurrentHashMap<>();

    /**
     * One parking of a lease. Deliberately not a record: {@code equals} must stay
     * identity so {@code handoffs.remove(key, parked)} cannot match a later parking
     * of the same lease.
     */
    private static final class Handoff {
        final LeaseHandle lease;

        Handoff(LeaseHandle lease) {
            this.lease = lease;
        }
    }
    private final List<Consumer<DeadLetterEntry>> deadLetterListeners = new CopyOnWriteArrayList<>();

    /**
     * Conversations whose dead letter this node has published to the shared stream
     * but whose erasure marker it has not yet read back successfully afterwards. An
     * erasure that wrote its marker and purged the stream between this node's check
     * and its publish leaves the erased turn in the shared stream; only a second
     * look after the publish finds it, so a look that could not be made is retried
     * until it can be. One id per conversation, so it stays small.
     */
    private final Set<String> publishedUnverified = ConcurrentHashMap.newKeySet();

    /**
     * Whether a retry of the forwarding is already scheduled: a run that defers
     * schedules the next one, and several chains of such retries would each keep
     * themselves alive for as long as the same marker stays unreadable.
     */
    private final AtomicBoolean forwardRetryScheduled = new AtomicBoolean();
    private volatile long cachedDeadLetterCount;
    private volatile long cachedTurnDeadLetters;
    private volatile long turnDeadLettersAt;
    private volatile long deadLetterCountAt;

    /**
     * Read before a locally kept dead letter is forwarded: a node that was cut off
     * from NATS during a GDPR erasure missed both the cluster stop and the purge of
     * the shared stream, and forwarding its copy afterwards re-published the erased
     * turn's input to every node. Lazily resolved (and absent in unit tests) so the
     * coordinator does not pull the memory store into its own construction.
     */
    @Inject
    Instance<IConversationMemoryStore> memoryStore;

    /**
     * The shared record of conversations under GDPR erasure. A conversation's
     * snapshot outlives the shared purge of its dead letters by several erasure
     * steps, so the existence check alone cannot tell a forwarding node that the
     * conversation is already condemned. Lazily resolved and absent in unit tests,
     * like {@link #memoryStore}.
     */
    @Inject
    Instance<ErasureTombstones> erasureTombstones;

    @Inject
    public ClusterConversationCoordinator(IRuntime runtime, MeterRegistry meterRegistry, NatsLeaseManager leases,
            JetStreamDeadLetterStore deadLetterStore, NatsConnectionManager connections, ClusterPresence presence,
            @ConfigProperty(name = "eddi.coordinator.max-active-conversations", defaultValue = "10000") int maxActiveConversations,
            @ConfigProperty(name = "eddi.coordinator.max-dead-letters", defaultValue = "1000") int maxDeadLetters) {
        this(runtime, meterRegistry, leases, deadLetterStore, connections, presence, maxActiveConversations, maxDeadLetters,
                connections.config().leaseAcquireTimeout());
        presence.contribute(this::presenceNumbers);
        connections.onConnected(this::forwardLocalDeadLetters);
        // Not only on reconnect: a dead letter can fall back locally while NATS is up
        // (a
        // stream that is still electing its leader), and would otherwise wait for the
        // next
        // disconnection to be shared.
        connections.scheduler().scheduleWithFixedDelay(() -> {
            try {
                if (connections.isConnected() && (localDeadLetterCount() > 0 || !publishedUnverified.isEmpty())) {
                    forwardLocalDeadLetters();
                }
            } catch (RuntimeException e) {
                // An exception that leaves a fixed-delay task cancels every later run.
                LOGGER.warnf(e, "Forwarding node-local dead letters failed; it is retried");
            }
        }, LOCAL_FORWARD_SECONDS, LOCAL_FORWARD_SECONDS, TimeUnit.SECONDS);
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
        numbers.put("localDeadLetters", localDeadLetterCount());
        return numbers;
    }

    /**
     * Acquires the lease asynchronously, then hands the task to the runtime. Called
     * under the queue monitor, so it must not do I/O — and does none: the lease
     * manager only registers a waiter and schedules the attempt.
     */
    @Override
    protected void execute(String conversationId, BlockingQueue<Callable<Void>> queue, Callable<Void> callable) {
        Handoff parked = handoffs.remove(conversationId);
        LeaseHandle handedOver = parked == null ? null : parked.lease;
        if (handedOver != null) {
            if (!handedOver.isLost()) {
                try {
                    continuations.execute(() -> runUnderLease(conversationId, queue, callable, handedOver));
                    return;
                } catch (RejectedExecutionException e) {
                    leases.release(handedOver);
                    throw e;
                }
            }
            leases.release(handedOver);
        }
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
            runtime.submitCallable(callable, new IRuntime.ITurnExecution<>() {
                @Override
                public void onComplete(Void result) {
                    totalProcessed.incrementAndGet();
                    releaseOrHandOver(conversationId, queue, lease);
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
     * After a turn: hands the lease straight to the next turn of the conversation
     * queued on this node, if there is one and the lease manager allows it (the
     * lease is healthy and no other node is waiting for it), instead of releasing
     * it and acquiring it again — which cost the queued turn several NATS round
     * trips and let a turn arriving on another node in between find the lease held.
     * {@link #execute} takes the parked lease while {@code submitNext} runs; a
     * lease nobody took is released.
     * <p>
     * The cleanup removes this call's own {@link Handoff}, not "the lease": once
     * {@code submitNext} has handed the lease on, the next turn can run to its end
     * and park the very same lease again before this thread reaches its
     * {@code finally}. Matching on the lease took that new parking, released the
     * lease, and made the turn after it acquire afresh — NATS round trips, and a
     * window for another node to take the conversation.
     */
    private void releaseOrHandOver(String conversationId, BlockingQueue<Callable<Void>> queue, LeaseHandle lease) {
        if (!leases.handOver(lease)) {
            leases.release(lease);
            submitNext(conversationId, queue);
            return;
        }
        Handoff parked = new Handoff(lease);
        handoffs.put(conversationId, parked);
        try {
            submitNext(conversationId, queue);
        } finally {
            if (handoffs.remove(conversationId, parked)) {
                leases.release(lease); // no queued turn took it
            }
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
        long timestamp = System.currentTimeMillis();
        Map<String, Object> turn = describe(task);
        DeadLetterClassifier.Classification classification = DeadLetterClassifier.classify(failure, turn);
        String id;
        try {
            id = deadLetterStore.append(conversationId, error, timestamp, turn, classification.reason(), classification.fence());
            totalDeadLettered.incrementAndGet();
            deadLetterCountAt = 0;
        } catch (ClusterUnavailableException e) {
            LOGGER.warnf("Dead letter of conversation %s kept node-locally: NATS unavailable (%s)", sanitize(conversationId),
                    e.getMessage());
            // The id this call recorded: reading the ring's last entry back could name
            // another failure's entry under concurrent dead letters.
            id = recordLocalDeadLetter(conversationId, failure, task);
            scheduleForward();
        }
        DeadLetterEntry created = new DeadLetterEntry(id, conversationId, error, timestamp, null, turn, classification.reason(),
                localNodeId(), classification.fence());
        for (Consumer<DeadLetterEntry> listener : deadLetterListeners) {
            try {
                listener.accept(created);
            } catch (RuntimeException e) {
                LOGGER.debugf("Dead-letter listener failed: %s", e.getMessage());
            }
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
        if (scheduler == null || !forwardRetryScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.schedule(() -> {
                forwardRetryScheduled.set(false);
                try {
                    if (connections.isConnected()) {
                        forwardLocalDeadLetters();
                    }
                } catch (RuntimeException e) {
                    LOGGER.warnf(e, "Retrying the forwarding of node-local dead letters failed; the periodic pass tries again");
                }
            }, FORWARD_RETRY_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            forwardRetryScheduled.set(false);
            // the reconnect path forwards them when this node comes back
        }
    }

    /**
     * {@link #verifyPublished(String)} for every publication still unverified.
     *
     * @return whether none is left
     */
    private boolean verifyPublished() {
        boolean allVerified = true;
        for (String conversationId : List.copyOf(publishedUnverified)) {
            allVerified &= verifyPublished(conversationId);
        }
        return allVerified;
    }

    /**
     * The look after a publish: a conversation erased in the meantime has its
     * shared dead letters purged again; one that is not is done with. A marker that
     * cannot be read, or a purge that fails, leaves the conversation unverified for
     * the next attempt instead of escaping into the caller.
     *
     * @return whether the publication is now verified
     */
    private boolean verifyPublished(String conversationId) {
        Erasure state = erasureState(conversationId);
        if (state == Erasure.UNKNOWN) {
            return false;
        }
        if (state == Erasure.ERASED) {
            try {
                deadLetterStore.purgeConversation(conversationId);
                LOGGER.infof("Purged the dead letters of conversation %s again: it was erased while they were published",
                        sanitize(conversationId));
            } catch (RuntimeException e) {
                // ClusterUnavailableException, or the stream management failing mid-disconnect:
                // either way the purge is retried, and the caller's pass goes on
                LOGGER.warnf("Purging the published dead letters of an erased conversation failed; it is retried: %s", e.getMessage());
                return false;
            }
        }
        publishedUnverified.remove(conversationId);
        return true;
    }

    /** Called after every dead letter this node records (shared or local). */
    public void onDeadLetter(Consumer<DeadLetterEntry> listener) {
        deadLetterListeners.add(listener);
    }

    @Override
    protected String localNodeId() {
        NodeIdentity node = connections.node();
        return node == null ? null : node.nodeId();
    }

    /**
     * Moves the dead letters this node had to keep locally while NATS was
     * unreachable into the shared stream, so every node lists them and they survive
     * this node's restart. Runs on every (re)connect; stops at the first failure
     * and tries the rest on the next one.
     *
     * @return how many entries were forwarded
     */
    public synchronized int forwardLocalDeadLetters() {
        int forwarded = 0;
        // Publications whose erasure check could not be completed last time come first
        boolean deferred = !verifyPublished();
        for (DeadLetterEntry local : super.getDeadLetters()) {
            Erasure erasure = erasureState(local.conversationId());
            if (erasure == Erasure.UNKNOWN) {
                // The shared marker cannot be read (NATS dropped again): an erasure may be
                // under way, so the entry stays here and is retried.
                LOGGER.debugf("Forwarding local dead letter %s deferred: the erasure marker could not be read", sanitize(local.id()));
                deferred = true;
                continue;
            }
            if (erasure == Erasure.ERASED) {
                discardErased(local);
                continue;
            }
            Existence existence = conversationExistence(local.conversationId());
            if (existence == Existence.UNKNOWN) {
                // The store could not be asked: an erasure may have finished while this node
                // was cut
                // off, and publishing now would put the erased input back into the shared
                // stream with no
                // later purge to remove it. Keep the entry here and try again shortly.
                LOGGER.debugf("Forwarding local dead letter %s deferred: its conversation could not be checked", sanitize(local.id()));
                deferred = true;
                continue;
            }
            if (existence == Existence.GONE) {
                super.discardDeadLetter(local.id());
                LOGGER.infof("Discarded local dead letter %s instead of forwarding it: its conversation no longer exists "
                        + "(erased or deleted while this node was cut off)", sanitize(local.id()));
                continue;
            }
            try {
                deadLetterStore.append(local.conversationId(), local.error(), local.timestamp(), local.turn(), local.reason(),
                        local.fence());
            } catch (ClusterUnavailableException e) {
                LOGGER.debugf("Forwarding local dead letters paused: %s", e.getMessage());
                break;
            }
            // The marker may have been written between the check above and the append,
            // when the erasure's shared purge had already run: whatever was just
            // published is purged again. The entry is in the shared stream now, so the
            // local copy goes; what is left to do is remembered by id and retried when
            // the marker cannot be read, or the purge cannot be made, right now.
            publishedUnverified.add(local.conversationId());
            super.discardDeadLetter(local.id());
            forwarded++;
            if (!verifyPublished(local.conversationId())) {
                deferred = true;
            }
        }
        if (deferred) {
            scheduleForward();
        }
        if (forwarded > 0) {
            deadLetterCountAt = 0;
            LOGGER.infof("Forwarded %d dead letter(s) kept on this node while NATS was unreachable to the shared stream", forwarded);
        }
        return forwarded;
    }

    /** What the shared erasure marker says about a conversation. */
    enum Erasure {
        ERASED, NOT_ERASED, UNKNOWN
    }

    /**
     * Reads the shared erasure marker. Nothing to read it from (unit tests) is "not
     * erased"; an unreachable bucket is {@link Erasure#UNKNOWN}, never "not
     * erased".
     */
    Erasure erasureState(String conversationId) {
        if (conversationId == null || erasureTombstones == null || !erasureTombstones.isResolvable()) {
            return Erasure.NOT_ERASED;
        }
        try {
            return erasureTombstones.get().isMarked(conversationId) ? Erasure.ERASED : Erasure.NOT_ERASED;
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not read the erasure marker of conversation %s: %s", sanitize(conversationId), e.getMessage());
            return Erasure.UNKNOWN;
        }
    }

    private void discardErased(DeadLetterEntry local) {
        super.discardDeadLetter(local.id());
        LOGGER.infof("Discarded local dead letter %s instead of forwarding it: its conversation is marked as erased", sanitize(local.id()));
    }

    /**
     * Whether the dead letter's conversation is provably gone. Anything uncertain —
     * no store, an unreadable store — keeps the dead letter: losing one is worse
     * than forwarding one that a later erasure purges again.
     */
    boolean conversationGone(String conversationId) {
        return conversationExistence(conversationId) == Existence.GONE;
    }

    /**
     * Whether a conversation provably exists, provably does not, or could not be
     * told.
     */
    enum Existence {
        EXISTS, GONE, UNKNOWN
    }

    /**
     * {@code getConversationState} answers {@code null} both for a missing document
     * and for a legacy document that stores no state, so a {@code null} is only a
     * candidate: the document itself decides. An unreadable store is
     * {@link Existence#UNKNOWN}, never "gone" and never "exists".
     */
    Existence conversationExistence(String conversationId) {
        if (conversationId == null || memoryStore == null || !memoryStore.isResolvable()) {
            return Existence.EXISTS; // nothing to check against: keep the previous behaviour, forward
        }
        try {
            var store = memoryStore.get();
            if (store.getConversationState(conversationId) != null) {
                return Existence.EXISTS;
            }
            try {
                return store.loadConversationMemorySnapshot(conversationId) == null ? Existence.GONE : Existence.EXISTS;
            } catch (IResourceStore.ResourceNotFoundException e) {
                return Existence.GONE;
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not check conversation %s before forwarding its dead letter: %s", sanitize(conversationId), e.getMessage());
            return Existence.UNKNOWN;
        }
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

    /**
     * Turn dead letters waiting in the shared stream (cached for a few seconds; the
     * last value while NATS is unreachable).
     */
    public long sharedDeadLetterCount() {
        long now = System.currentTimeMillis();
        if (now - turnDeadLettersAt > DEAD_LETTER_COUNT_CACHE_MILLIS || deadLetterCountAt == 0) {
            try {
                cachedTurnDeadLetters = deadLetterStore.countTurns();
                turnDeadLettersAt = now;
            } catch (ClusterUnavailableException e) {
                // keep the last value
            }
        }
        return cachedTurnDeadLetters;
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

    /**
     * The dead letters of one conversation, shared and node-local, oldest first.
     * The shared ones are read through the conversation's own subject.
     */
    public List<DeadLetterEntry> getDeadLettersOf(String conversationId, int limit) {
        List<DeadLetterEntry> entries = new ArrayList<>();
        try {
            entries.addAll(deadLetterStore.listConversation(conversationId, limit));
        } catch (ClusterUnavailableException e) {
            LOGGER.debugf("Dead-letter stream unavailable: %s", e.getMessage());
        }
        for (DeadLetterEntry local : super.getDeadLetters()) {
            if (entries.size() < limit && conversationId.equals(local.conversationId())) {
                entries.add(local);
            }
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
        // The marker goes first: a node holding a local copy that reconnects at any
        // point from here on finds the conversation condemned, not merely "still
        // existing" (its snapshot is deleted several erasure steps later). A failed
        // write must not leave the shared stream unpurged, nor pass for a clean
        // erasure: the purge runs, then the failure is reported to the cascade.
        RuntimeException markFailure = null;
        if (erasureTombstones != null && erasureTombstones.isResolvable()) {
            try {
                erasureTombstones.get().mark(conversationId);
            } catch (RuntimeException e) {
                markFailure = e;
            }
        }
        purged += deadLetterStore.purgeConversation(conversationId);
        deadLetterCountAt = 0;
        if (markFailure != null) {
            throw markFailure;
        }
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
