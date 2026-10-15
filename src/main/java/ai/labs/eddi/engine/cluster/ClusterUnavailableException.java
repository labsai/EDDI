/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

/**
 * NATS is not reachable right now (not connected, or the call failed or timed
 * out). Thrown immediately rather than after a timeout: every caller has a
 * degraded-mode decision to make, and making it fast is the point — a blocked
 * turn thread is worse than a local decision.
 */
public class ClusterUnavailableException extends RuntimeException {
    public ClusterUnavailableException(String message) {
        super(message);
    }

    public ClusterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
