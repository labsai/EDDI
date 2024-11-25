package ai.labs.eddi.engine.runtime.model;

import ai.labs.eddi.engine.model.Deployment;

public record DeploymentEvent(String botId, Integer version, Deployment.Environment environment,
                              Deployment.Status status) {
}



