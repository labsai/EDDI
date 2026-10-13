/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.lease;

/**
 * A lease could not be acquired: another node kept it for longer than the wait
 * allowed, or NATS is unreachable and the degraded policy is {@code reject}.
 */
public class LeaseUnavailableException extends RuntimeException {

    public enum Reason {
        /** Another node held the lease for the whole wait. */
        TIMEOUT,
        /** NATS is unreachable and {@code eddi.cluster.degraded.turns=reject}. */
        DEGRADED,
        /** The node is shutting down. */
        SHUTTING_DOWN,
        /**
         * An administrator drained the node: it takes no new leases until it is
         * undrained or restarted.
         */
        DRAINING
    }

    private final Reason reason;
    private final String holderNode;

    public LeaseUnavailableException(Reason reason, String holderNode, String message) {
        super(message);
        this.reason = reason;
        this.holderNode = holderNode;
    }

    public Reason reason() {
        return reason;
    }

    /** The node that held the lease when the wait ended, if known. */
    public String holderNode() {
        return holderNode;
    }
}
