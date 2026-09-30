/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal.readiness;

import java.util.List;

public interface IAgentsReadiness {

    void setAgentsReadiness(boolean isReady);

    boolean isAgentsReady();

    /**
     * Records the deployments that are meant to be deployed but are in ERROR, as
     * {@code environment/agentId/version}. Reported by the readiness check as data;
     * they do not make the instance not-ready — see
     * {@code AgentsReadinessHealthCheck}.
     */
    default void setAgentsInError(List<String> agentsInError) {
    }

    /** The deployments last recorded by {@link #setAgentsInError(List)}. */
    default List<String> getAgentsInError() {
        return List.of();
    }
}
