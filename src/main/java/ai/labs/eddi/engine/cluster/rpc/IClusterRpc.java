/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.rpc;

import java.util.Map;
import java.util.Optional;
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

    /** Serves {@code op} on this node. */
    void handle(String op, Function<Map<String, Object>, Map<String, Object>> handler);

    default boolean isClustered() {
        return false;
    }
}
