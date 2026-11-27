/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.rpc;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Node-addressed control: the few operations that must reach the node where
 * something is <em>executing</em> — cancelling a running turn, controlling a
 * live group discussion, stopping a user's in-flight work for GDPR erasure,
 * reading another node's queue depths.
 * <p>
 * Core NATS request-reply on {@code eddi.<prefix>.rpc.<nodeId>.<op>}; scatter
 * on {@code eddi.<prefix>.rpc.all.<op>} collects replies until every known
 * member answered or {@code eddi.nats.request-timeout} passed. Every call fails
 * soft: an unreachable node yields no answer, and the caller falls back to its
 * database path. Single-node mode ({@link LocalClusterRpc}) answers nothing.
 */
public interface IClusterRpc {

    /** Operation names. */
    String CONVERSATION_CANCEL = "conversation-cancel";
    String GROUP_CONTROL = "group-control";
    String GDPR_STOP = "gdpr-stop";
    /**
     * The drain barrier of an explicit conversation delete: every node waits for
     * its async erasure writers and answers {@code drained=true} (see
     * {@code ErasureGuard}).
     */
    String ERASURE_DRAIN = "erasure-drain";
    String COORDINATOR_STATUS = "coordinator-status";

    /**
     * Calls {@code op} on one node.
     *
     * @return the reply, or empty when the node did not answer
     */
    Optional<Map<String, Object>> call(String nodeId, String op, Map<String, Object> request);

    /**
     * Calls {@code op} on every OTHER node.
     *
     * @return node id → reply, for the nodes that answered in time
     */
    Map<String, Map<String, Object>> callAll(String op, Map<String, Object> request);

    /**
     * Like {@link #callAll(String, Map)}, but waits up to {@code wait} and stops
     * early once {@code expectedReplies} nodes answered (0: wait the whole time).
     * Any {@code expectedReplies} nodes end the wait, whichever they are; a caller
     * that needs particular nodes to answer uses {@link #callAllAwaiting}.
     */
    default Map<String, Map<String, Object>> callAll(String op, Map<String, Object> request, Duration wait, int expectedReplies) {
        return callAll(op, request);
    }

    /**
     * Like {@link #callAll(String, Map, Duration, int)}, but stops early only once
     * <em>every</em> node in {@code expectedNodes} answered (empty: wait the whole
     * time). A node that joined after the caller read {@link #expectedPeerIds()}
     * still answers, but cannot end the wait in place of one of the expected nodes.
     * For a caller that must know <em>which</em> nodes confirmed — GDPR erasure,
     * whose drain barrier holds only when each node it counted has drained.
     */
    default Map<String, Map<String, Object>> callAllAwaiting(String op, Map<String, Object> request, Duration wait,
                                                             Set<String> expectedNodes) {
        return callAll(op, request, wait, expectedNodes.size());
    }

    /** Serves {@code op} on this node. */
    void handle(String op, Function<Map<String, Object>, Map<String, Object>> handler);

    /**
     * How many OTHER nodes a {@link #callAll} is expected to reach, from the
     * current presence view. A caller for which a missing answer matters (GDPR
     * erasure) compares it with the replies it got: {@code callAll} itself returns
     * a partial map without saying so.
     */
    default int expectedPeers() {
        return 0;
    }

    /**
     * The ids of the OTHER nodes a {@link #callAll} is expected to reach, read now
     * from the presence view — the nodes {@link #expectedPeers()} counts. A caller
     * that checks the replies node by node (GDPR erasure) compares the reply keys
     * with these.
     */
    default Set<String> expectedPeerIds() {
        return Set.of();
    }

    default boolean isClustered() {
        return false;
    }
}
