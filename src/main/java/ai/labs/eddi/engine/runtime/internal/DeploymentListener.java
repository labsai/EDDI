package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.model.DeploymentEvent;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static ai.labs.eddi.engine.model.Deployment.Status.ERROR;
import static ai.labs.eddi.engine.model.Deployment.Status.READY;

@ApplicationScoped
public class DeploymentListener implements IDeploymentListener {
    private final Map<String, CompletableFuture<Void>> deploymentFutures = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Void> getRegisteredDeploymentEvent(String botId, Integer version) {
        return deploymentFutures.get(createKey(botId, version));
    }

    public CompletableFuture<Void> registerBotDeployment(String botId, Integer version) {
        return deploymentFutures.computeIfAbsent(createKey(botId, version), k -> new CompletableFuture<>());
    }

    public void onDeploymentEvent(DeploymentEvent event) {
        if (event.status() == READY) {
            String key = createKey(event.botId(), event.version());
            CompletableFuture<Void> future = deploymentFutures.remove(key);
            if (future != null) {
                future.complete(null); // Mark deployment as successful
            }
        } else if (event.status() == ERROR) {
            String key = createKey(event.botId(), event.version());
            CompletableFuture<Void> future = deploymentFutures.remove(key);
            if (future != null) {
                future.completeExceptionally(new IllegalStateException(
                        "Deployment failed for BotId: " + event.botId() + ", Version: " + event.version()));
            }
        }
    }

    private static String createKey(String botId, Integer version) {
        return botId + ":" + version;
    }
}
