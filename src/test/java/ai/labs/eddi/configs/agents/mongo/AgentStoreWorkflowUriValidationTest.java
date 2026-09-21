/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.mongo;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AgentStore workflow URI validation")
class AgentStoreWorkflowUriValidationTest {

    private static final String WORKFLOW = "eddi://ai.labs.workflow/workflowstore/workflows/aabbccdd11223344eeff5566?version=1";

    private static AgentConfiguration withWorkflows(String... uris) {
        AgentConfiguration config = new AgentConfiguration();
        List<URI> workflows = new ArrayList<>();
        for (String uri : uris) {
            workflows.add(URI.create(uri));
        }
        config.setWorkflows(workflows);
        return config;
    }

    @Test
    @DisplayName("accepts versioned workflow resource URIs")
    void acceptsWorkflowUris() {
        assertDoesNotThrow(() -> AgentStore.validateWorkflowUris(withWorkflows(WORKFLOW,
                "eddi://ai.labs.workflow/workflowstore/workflows/ff00112233445566aa77?version=12")));
    }

    @Test
    @DisplayName("accepts an agent without workflows")
    void acceptsNoWorkflows() {
        assertDoesNotThrow(() -> AgentStore.validateWorkflowUris(new AgentConfiguration()));
        assertDoesNotThrow(() -> AgentStore.validateWorkflowUris(withWorkflows()));
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {
            "eddi://ai.labs.workflow/workflowstore/workflows/zzz?version=1",
            "eddi://ai.labs.workflow/workflowstore/workflows/aabbccdd11223344eeff5566",
            "eddi://ai.labs.workflow/workflowstore/workflows/aabbccdd11223344eeff5566?version=0",
            "eddi://ai.labs.rules/rulestore/rulesets/aabbccdd11223344eeff5566?version=1",
            "http://example.com/workflows/aabbccdd11223344eeff5566?version=1"
    })
    @DisplayName("rejects anything that is not a versioned workflow resource URI, naming its index")
    void rejectsMalformedUris(String uri) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentStore.validateWorkflowUris(withWorkflows(WORKFLOW, uri)));

        assertTrue(e.getMessage().contains("workflows[1]"), "the message must point at the offending entry: " + e.getMessage());
        assertTrue(e.getMessage().contains(uri), "the message must echo the rejected URI: " + e.getMessage());
    }
}
