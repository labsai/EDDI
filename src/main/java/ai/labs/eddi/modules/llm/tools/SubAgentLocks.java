/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Per-agent coordination between the end-of-conversation teardown of a
 * sub-agent and {@code retain_agent} / {@code unretain_agent}, cluster-wide.
 * <p>
 * Teardown reads the agent's retain flag and then undeploys and deletes it. A
 * retain written in between would report success for an agent that is deleted
 * anyway — on any node. So both take {@link #acquire the agent's guard}: an
 * in-node striped lock (the fast path between threads of one node) and the
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

    /** A held guard; closing releases the lease and the in-node lock. */
    public static final class Guard implements AutoCloseable {
        private final ReentrantLock lock;
        private final IConversationLeaseManager leaseManager;
        private final LeaseHandle lease;
        private boolean closed;

        private Guard(ReentrantLock lock, IConversationLeaseManager leaseManager, LeaseHandle lease) {
            this.lock = lock;
            this.leaseManager = leaseManager;
            this.lease = lease;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (leaseManager != null && lease != null) {
                    leaseManager.release(lease);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Takes {@code agentId}'s guard: the in-node lock, then the cluster lease, each
     * within {@link #WAIT}.
     *
     * @param leaseManager
     *            the cluster lease manager; {@code null} guards this node only
     * @return the held guard, or empty when either could not be had in time
     */
    public static Optional<Guard> acquire(IConversationLeaseManager leaseManager, String agentId) {
        ReentrantLock lock = lockFor(agentId);
        try {
            if (!lock.tryLock(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                return Optional.empty();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (leaseManager == null) {
            return Optional.of(new Guard(lock, null, null));
        }
        try {
            LeaseHandle lease = leaseManager.acquireKey(LEASE_PREFIX + agentId, WAIT).toCompletableFuture()
                    .get(WAIT.toMillis() + 1_000, TimeUnit.MILLISECONDS);
            return Optional.of(new Guard(lock, leaseManager, lease));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lock.unlock();
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.debugf("[DYNAMIC] Lease of sub-agent '%s' not acquired: %s", sanitize(agentId), e.getMessage());
            lock.unlock();
            return Optional.empty();
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
