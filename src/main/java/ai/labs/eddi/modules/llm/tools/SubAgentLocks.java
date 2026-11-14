/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Per-agent coordination between the end-of-conversation teardown of a
 * sub-agent and {@code retain_agent} / {@code unretain_agent}, cluster-wide.
 * <p>
 * Teardown reads the agent's retain flag and then undeploys and deletes it. A
 * retain written in between would report success for an agent that is deleted
 * anyway — on any node. So both run {@link #withGuard under the agent's guard}:
 * an in-node striped lock (the fast path between threads of one node) and the
 * cluster lease {@value #LEASE_PREFIX}{@code <agentId>} of the
 * {@link IConversationLeaseManager} (the same leases that serialise
 * conversation turns: renewed while held, expiring with a node that dies).
 * Teardown holds the guard across read, undeploy and delete; a retain or
 * unretain that cannot get it within a short bounded wait is refused, a
 * teardown that cannot get it skips the agent this time rather than deleting it
 * unprotected. With clustering off, the lease manager is the local no-op one
 * and the in-node lock is the whole guard.
 * <p>
 * Striped: a fixed set of locks, so nothing grows with the number of agents.
 */
public final class SubAgentLocks {

    private static final Logger LOGGER = Logger.getLogger(SubAgentLocks.class);

    /** Lease key prefix of a sub-agent's guard (KV-safe characters only). */
    public static final String LEASE_PREFIX = "subagent.";

    /** How long a retain, unretain or teardown waits for an agent's guard. */
    public static final Duration WAIT = Duration.ofSeconds(2);

    private static final int STRIPES = 64;
    private static final ReentrantLock[] LOCKS = new ReentrantLock[STRIPES];
    private static final Set<String> TEARING_DOWN = ConcurrentHashMap.newKeySet();

    static {
        for (int i = 0; i < STRIPES; i++) {
            LOCKS[i] = new ReentrantLock();
        }
    }

    private SubAgentLocks() {
    }

    /**
     * The view of a held guard that {@link #withGuard}'s body gets: whether it
     * still excludes the other nodes. Owns nothing — {@link #withGuard} takes and
     * releases the lock and the lease itself.
     */
    public static final class Guard {
        private final IConversationLeaseManager leaseManager;
        private final LeaseHandle lease;

        private Guard(IConversationLeaseManager leaseManager, LeaseHandle lease) {
            this.leaseManager = leaseManager;
            this.lease = lease;
        }

        /**
         * Whether this guard still excludes every other node: true for a node-local
         * guard, and for a cluster lease until it is lost (past its validity, or taken
         * over). Checked right before each irreversible step.
         */
        public boolean stillHeld() {
            return lease == null || !lease.isLost();
        }

        /**
         * Whether this guard coordinates with the other nodes at all. False only when
         * clustering is on and the lease manager handed out an unfenced handle (its
         * degraded mode with NATS unreachable): node-local ordering only. Without
         * clustering, the in-node lock is all there is to coordinate, so true.
         */
        public boolean coordinated() {
            return leaseManager == null || !leaseManager.isClustered() || (lease != null && lease.fence() != null);
        }
    }

    /**
     * Runs {@code body} holding {@code agentId}'s guard: the in-node lock, then the
     * cluster lease, each taken within {@link #WAIT} and both released when the
     * body returns or throws. Lock and unlock live in this one method.
     *
     * @param leaseManager
     *            the cluster lease manager; {@code null} guards this node only
     * @param ifUnavailable
     *            what to return when either could not be had in time (or the wait
     *            was interrupted; the interrupt is restored)
     * @return what {@code body} returned, or {@code ifUnavailable}'s value
     */
    public static <T> T withGuard(IConversationLeaseManager leaseManager, String agentId, Function<Guard, T> body,
                                  Supplier<T> ifUnavailable) {
        ReentrantLock lock = lockFor(agentId);
        try {
            if (!lock.tryLock(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                return ifUnavailable.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ifUnavailable.get();
        }
        try {
            if (leaseManager == null) {
                return body.apply(new Guard(null, null));
            }
            LeaseHandle lease;
            try {
                lease = leaseManager.acquireKey(LEASE_PREFIX + agentId, WAIT).toCompletableFuture().get(WAIT.toMillis() + 1_000,
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ifUnavailable.get();
            } catch (Exception e) {
                LOGGER.debugf("[DYNAMIC] Lease of sub-agent '%s' not acquired: %s", sanitize(agentId), e.getMessage());
                return ifUnavailable.get();
            }
            try {
                return body.apply(new Guard(leaseManager, lease));
            } finally {
                if (lease != null) {
                    leaseManager.release(lease);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** The in-node lock guarding {@code agentId}. */
    public static ReentrantLock lockFor(String agentId) {
        return LOCKS[Math.floorMod(agentId == null ? 0 : agentId.hashCode(), STRIPES)];
    }

    /**
     * Called with the agent's guard held, once teardown has decided to delete it.
     */
    public static void markTearingDown(String agentId) {
        TEARING_DOWN.add(agentId);
    }

    /** Called with the agent's guard held, when teardown is over. */
    public static void clearTearingDown(String agentId) {
        TEARING_DOWN.remove(agentId);
    }

    /**
     * Whether teardown on this node has decided to delete {@code agentId} and is
     * still at it.
     */
    public static boolean isTearingDown(String agentId) {
        return agentId != null && TEARING_DOWN.contains(agentId);
    }
}
