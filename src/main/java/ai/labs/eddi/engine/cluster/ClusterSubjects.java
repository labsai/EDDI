/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

/**
 * NATS subject names. Every subject is rooted at {@code eddi.<prefix>}, so two
 * EDDI deployments sharing one NATS cluster (with different
 * {@code eddi.nats.prefix} values) never see each other's events, RPCs or dead
 * letters, and a NATS account restricted to {@code eddi.>} covers all of them.
 */
public final class ClusterSubjects {

    private final String root;

    public ClusterSubjects(String prefix) {
        this.root = "eddi." + prefix;
    }

    public String root() {
        return root;
    }

    /** Core NATS: a lease was released. */
    public String leaseReleased(String leaseKey) {
        return root + ".lease.released." + leaseKey;
    }

    public String leaseReleasedWildcard() {
        return root + ".lease.released.>";
    }

    /** JetStream event stream subjects. */
    public String event(String type) {
        return root + ".evt." + type;
    }

    public String eventWildcard() {
        return root + ".evt.>";
    }

    /** Node-addressed request-reply. */
    public String rpc(String nodeId, String op) {
        return root + ".rpc." + nodeId + "." + op;
    }

    public String rpcAll(String op) {
        return root + ".rpc.all." + op;
    }

    public String rpcNodeWildcard(String nodeId) {
        return root + ".rpc." + nodeId + ".>";
    }

    public String rpcAllWildcard() {
        return root + ".rpc.all.>";
    }

    /** Dead letters of conversation turns. */
    public String deadLetterTurn(String conversationKey) {
        return root + ".dlq.turn." + conversationKey;
    }

    public String deadLetterTurnWildcard() {
        return root + ".dlq.turn.>";
    }

    /** Audit-ledger entries that could not be stored. */
    public String deadLetterAudit() {
        return root + ".dlq.audit";
    }

    public String deadLetterWildcard() {
        return root + ".dlq.>";
    }
}
