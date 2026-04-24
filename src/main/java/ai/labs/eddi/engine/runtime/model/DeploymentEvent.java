/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.model;

import ai.labs.eddi.engine.model.Deployment;

public record DeploymentEvent(String agentId, Integer version, Deployment.Environment environment, Deployment.Status status) {
}
