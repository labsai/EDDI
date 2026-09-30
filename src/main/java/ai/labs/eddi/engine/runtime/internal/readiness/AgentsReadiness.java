/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal.readiness;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class AgentsReadiness implements IAgentsReadiness {
    private boolean agentsAreReady = false;
    private volatile List<String> agentsInError = List.of();

    @Override
    public void setAgentsReadiness(boolean isReady) {
        agentsAreReady = isReady;
    }

    @Override
    public boolean isAgentsReady() {
        return agentsAreReady;
    }

    @Override
    public void setAgentsInError(List<String> agentsInError) {
        this.agentsInError = agentsInError == null ? List.of() : List.copyOf(agentsInError);
    }

    @Override
    public List<String> getAgentsInError() {
        return agentsInError;
    }
}
