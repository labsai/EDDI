/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

/**
 * The bucket is reachable but refuses this value, because it is larger than the
 * payload or message limit. Retrying the same value cannot succeed, unlike a
 * plain {@link ClusterUnavailableException} (an outage), which a later attempt
 * may. It is a subclass, so every caller that treats "the shared write failed"
 * alike keeps working; only the shared cache tells the two apart.
 */
public class ClusterValueRefusedException extends ClusterUnavailableException {
    public ClusterValueRefusedException(String message, Throwable cause) {
        super(message, cause);
    }
}
