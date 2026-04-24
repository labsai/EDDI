/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.model.DeploymentEvent;

import java.util.concurrent.CompletableFuture;

public interface IDeploymentListener {
    CompletableFuture<Void> getRegisteredDeploymentEvent(String agentId, Integer version);

    CompletableFuture<Void> registerAgentDeployment(String agentId, Integer version);

    void onDeploymentEvent(DeploymentEvent event);
}
