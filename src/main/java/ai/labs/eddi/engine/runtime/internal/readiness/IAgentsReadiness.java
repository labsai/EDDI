package ai.labs.eddi.engine.runtime.internal.readiness;

public interface IAgentsReadiness {

    void setAgentsReadiness(boolean isReady);

    boolean isAgentsReady();
}
