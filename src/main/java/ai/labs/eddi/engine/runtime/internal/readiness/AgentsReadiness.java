package ai.labs.eddi.engine.runtime.internal.readiness;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AgentsReadiness implements IAgentsReadiness {
    private boolean agentsAreReady = false;

    @Override
    public void setAgentsReadiness(boolean isReady) {
        agentsAreReady = isReady;
    }

    @Override
    public boolean isAgentsReady() {
        return agentsAreReady;
    }
}
