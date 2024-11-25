package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.model.DeploymentEvent;

import java.util.concurrent.CompletableFuture;

public interface IDeploymentListener {
    CompletableFuture<Void> getRegisteredDeploymentEvent(String botId, Integer version);

    CompletableFuture<Void> registerBotDeployment(String botId, Integer version);

    void onDeploymentEvent(DeploymentEvent event);
}
