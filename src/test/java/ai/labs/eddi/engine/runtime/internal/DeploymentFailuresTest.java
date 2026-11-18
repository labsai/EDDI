/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.lifecycle.exceptions.UnrecognizedExtensionException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.model.DeploymentFailure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("DeploymentFailures")
class DeploymentFailuresTest {

    @Test
    @DisplayName("an unknown step type, an invalid config and a missing reference each get their own code, found in the cause chain")
    void classifiesByCause() {
        assertEquals(DeploymentFailures.STEP_TYPE_UNKNOWN,
                DeploymentFailures.codeOf(new RuntimeException("x", new UnrecognizedExtensionException("Extension 'a' not found"))));
        assertEquals(DeploymentFailures.CONFIG_INVALID,
                DeploymentFailures.codeOf(new RuntimeException(new WorkflowConfigurationException("temperature must be 0..2"))));
        assertEquals(DeploymentFailures.REFERENCE_MISSING,
                DeploymentFailures.codeOf(new RuntimeException(new IResourceStore.ResourceNotFoundException("gone"))));
        assertEquals(DeploymentFailure.DEPLOYMENT_FAILED, DeploymentFailures.codeOf(new IllegalStateException("boom")));
        assertEquals(DeploymentFailure.DEPLOYMENT_FAILED, DeploymentFailures.codeOf(null));
    }

    @Test
    @DisplayName("the message is fixed text per code: nothing of the exception reaches it (no host, key or path)")
    void messageNeverCarriesTheCause() {
        var cause = new WorkflowConfigurationException("baseUrl https://internal-host.corp:8443/v1 apiKey=sk-secret-123 /etc/eddi/x");
        DeploymentFailure failure = DeploymentFailures.failureOf(new RuntimeException(cause), "agent1", 3);

        assertEquals(DeploymentFailures.CONFIG_INVALID, failure.code());
        assertFalse(failure.message().contains("internal-host"), failure.message());
        assertFalse(failure.message().contains("sk-secret"), failure.message());
        assertFalse(failure.message().contains("/etc/eddi"), failure.message());
        assertEquals(true, failure.message().contains("agent1") && failure.message().contains("v3"), failure.message());
    }

    @Test
    @DisplayName("an unclassified failure keeps the generic message the API always had")
    void genericMessageUnchanged() {
        DeploymentFailure failure = DeploymentFailures.failureOf(new IllegalStateException("x"), "a", 1);
        assertEquals(DeploymentFailure.DEPLOYMENT_FAILED, failure.code());
        assertEquals(AgentFactory.failureMessage("a", 1), failure.message());
    }
}
