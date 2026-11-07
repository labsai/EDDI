/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.rest;

import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.engine.setup.AgentSetupService.AgentSetupException;
import ai.labs.eddi.engine.setup.ApiAgentSyncService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The status and body {@link RestAgentSetup} answers with. A failure the setup
 * services do not map to a 4xx can carry an internal host, a driver or a store
 * in its message; the caller gets a correlation id for the server log instead.
 */
@DisplayName("RestAgentSetup")
class RestAgentSetupTest {

    /** A driver failure of the shape that used to reach the caller verbatim. */
    private static final String INTERNAL_DETAIL = "Timed out after 30000 ms while waiting for a server: mongo-0.internal:27017";

    private static final Pattern CORRELATION_ID = Pattern.compile("correlationId: [0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private AgentSetupService agentSetupService;
    private ApiAgentSyncService apiAgentSyncService;
    private RestAgentSetup rest;

    @BeforeEach
    void setUp() {
        agentSetupService = mock(AgentSetupService.class);
        apiAgentSyncService = mock(ApiAgentSyncService.class);
        rest = new RestAgentSetup(agentSetupService, apiAgentSyncService);
    }

    @Test
    @DisplayName("setupAgent: an unexpected failure is an opaque 500 with a correlation id")
    void setupAgentUnexpectedFailureIsOpaque() throws Exception {
        when(agentSetupService.setupAgent(any())).thenThrow(new RuntimeException(INTERNAL_DETAIL));

        assertOpaqueServerError(rest.setupAgent(null), "Agent setup failed");
    }

    @Test
    @DisplayName("createApiAgent: an unexpected failure is an opaque 500 with a correlation id")
    void createApiAgentUnexpectedFailureIsOpaque() throws Exception {
        when(agentSetupService.createApiAgent(any())).thenThrow(new RuntimeException(INTERNAL_DETAIL));

        assertOpaqueServerError(rest.createApiAgent(null), "API agent setup failed");
    }

    @Test
    @DisplayName("syncApiAgent: an unexpected failure is an opaque 500 with a correlation id")
    void syncApiAgentUnexpectedFailureIsOpaque() throws Exception {
        when(apiAgentSyncService.sync(any(), any())).thenThrow(new RuntimeException(INTERNAL_DETAIL));

        assertOpaqueServerError(rest.syncApiAgent("abcdef0123456789abcdef01", null), "API agent sync failed");
    }

    @Test
    @DisplayName("every 500 gets its own correlation id")
    void correlationIdIsPerFailure() throws Exception {
        when(agentSetupService.setupAgent(any())).thenThrow(new RuntimeException(INTERNAL_DETAIL));

        assertNotEquals(errorOf(rest.setupAgent(null)), errorOf(rest.setupAgent(null)));
    }

    @Test
    @DisplayName("a validation failure still reaches the caller verbatim as a 400")
    void validationFailureKeepsItsMessage() throws Exception {
        when(agentSetupService.setupAgent(any())).thenThrow(new AgentSetupException("System prompt is required"));

        Response response = rest.setupAgent(null);

        assertEquals(400, response.getStatus());
        assertEquals("System prompt is required", errorOf(response));
    }

    private static void assertOpaqueServerError(Response response, String context) {
        assertEquals(500, response.getStatus());
        String error = errorOf(response);
        assertTrue(error.startsWith(context), error);
        assertTrue(CORRELATION_ID.matcher(error).find(), error);
        assertFalse(error.contains("mongo-0.internal"), error);
        assertFalse(error.contains("Timed out"), error);
    }

    private static String errorOf(Response response) {
        return (String) ((Map<?, ?>) response.getEntity()).get("error");
    }
}
