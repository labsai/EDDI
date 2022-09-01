package ai.labs.eddi.engine.runtime.internal.readiness;

public interface IBotsReadiness {

    void setBotsReadiness(boolean isReady);

    boolean isBotsReady();
}
