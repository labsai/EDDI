package ai.labs.eddi.engine.runtime.internal.readiness;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BotsReadiness implements IBotsReadiness {
    private boolean botsAreReady = false;

    @Override
    public void setBotsReadiness(boolean isReady) {
        botsAreReady = isReady;
    }

    @Override
    public boolean isBotsReady() {
        return botsAreReady;
    }
}
