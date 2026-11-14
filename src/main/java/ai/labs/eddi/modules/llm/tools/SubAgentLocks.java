/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-agent coordination, on this node, between the end-of-conversation
 * teardown of a sub-agent and {@code retain_agent} / {@code unretain_agent}.
 * <p>
 * Teardown reads the agent's retain flag and then undeploys and deletes it. A
 * retain written in between would report success for an agent that is deleted
 * anyway. So teardown holds the agent's lock across read, undeploy and delete,
 * and marks the agent as being torn down; a retain that finds the mark is
 * refused with that reason, one that waits for the lock finds the agent gone.
 * Across nodes this is best-effort.
 * <p>
 * Striped: a fixed set of locks, so nothing grows with the number of agents.
 */
public final class SubAgentLocks {

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

    /** The lock guarding {@code agentId}'s retain flag and teardown. */
    public static ReentrantLock lockFor(String agentId) {
        return LOCKS[Math.floorMod(agentId == null ? 0 : agentId.hashCode(), STRIPES)];
    }

    /**
     * Called with the agent's lock held, once teardown has decided to delete it.
     */
    public static void markTearingDown(String agentId) {
        TEARING_DOWN.add(agentId);
    }

    /** Called with the agent's lock held, when teardown is over. */
    public static void clearTearingDown(String agentId) {
        TEARING_DOWN.remove(agentId);
    }

    /**
     * Whether teardown has decided to delete {@code agentId} and is still at it.
     */
    public static boolean isTearingDown(String agentId) {
        return agentId != null && TEARING_DOWN.contains(agentId);
    }
}
