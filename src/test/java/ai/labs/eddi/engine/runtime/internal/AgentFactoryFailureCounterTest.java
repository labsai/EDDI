/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.client.agents.IAgentStoreClientLibrary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * A counter created by its first increment starts at 1, and {@code rate()}
 * shows no increase for a series' first sample: the first failure of each
 * reason would be invisible on the "Failed deployments by reason" panel.
 */
@DisplayName("AgentFactory: failed-deployment counters")
class AgentFactoryFailureCounterTest {

    @Test
    @DisplayName("every reason's counter exists at 0 before the first deployment fails")
    void everyReasonIsRegisteredAtZero() {
        var registry = new SimpleMeterRegistry();

        new AgentFactory(mock(IAgentStoreClientLibrary.class), mock(IDeploymentListener.class), registry);

        assertEquals(5, DeploymentFailures.REASONS.size());
        for (String reason : DeploymentFailures.REASONS) {
            var counter = registry.find("eddi.deployment.failed").tag("reason", reason).counter();
            assertNotNull(counter, reason);
            assertEquals(0.0, counter.count(), reason);
        }
    }
}
