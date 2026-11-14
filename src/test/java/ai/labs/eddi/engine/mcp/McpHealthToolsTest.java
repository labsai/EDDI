/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentHealth;
import ai.labs.eddi.engine.api.IRestHealthPortfolio;
import ai.labs.eddi.engine.health.HealthPeriods;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("McpHealthTools — read-only health tools over the in-process REST beans")
class McpHealthToolsTest {

    private IRestAgentHealth health;
    private IRestHealthPortfolio portfolio;
    private IJsonSerialization json;
    private SecurityIdentity identity;
    private McpHealthTools tools;

    @BeforeEach
    void setUp() throws Exception {
        health = mock(IRestAgentHealth.class);
        portfolio = mock(IRestHealthPortfolio.class);
        json = mock(IJsonSerialization.class);
        identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.hasRole(anyString())).thenAnswer(inv -> "eddi-viewer".equals(inv.getArgument(0)));
        when(json.serialize(any())).thenReturn("{}");
        tools = new McpHealthTools(health, portfolio, json, identity, true);
    }

    @Test
    @DisplayName("read_agent_health passes the arguments through, origins split from a comma list")
    void readsHealth() {
        tools.readAgentHealth(" agent ", "7d", null, null, 3, "api, v1,,");
        verify(health).readHealth("agent", "7d", null, null, 3, List.of("api", "v1"));
    }

    @Test
    @DisplayName("compare and portfolio delegate; no status filter over MCP")
    void delegates() {
        tools.compareAgentVersionsHealth("agent", 7, 8, 14, null);
        verify(health).compareVersions("agent", 7, 8, 14, null);
        tools.listAgentHealth("30d", "traffic", 10, null);
        verify(portfolio).listAgentHealth("30d", "traffic", null, 10, null);
    }

    @Test
    @DisplayName("a missing agent id, a bad parameter and a refusal come back as coded errors")
    void errors() {
        assertTrue(tools.readAgentHealth(" ", null, null, null, null, null).contains("BAD_REQUEST"));
        when(health.readHealth(any(), any(), any(), any(), any(), any())).thenThrow(HealthPeriods.badRequest("period must be one of 7d"));
        assertTrue(tools.readAgentHealth("agent", "1d", null, null, null, null).contains("bad_request"));
        when(health.compareVersions(any(), any(), any(), any(), any())).thenThrow(new ForbiddenException("Access denied"));
        assertTrue(tools.compareAgentVersionsHealth("agent", 1, 2, null, null).contains("FORBIDDEN"));
    }

    @Test
    @DisplayName("roles outside the health tier are refused before anything is read")
    void tier() {
        SecurityIdentity user = mock(SecurityIdentity.class);
        when(user.isAnonymous()).thenReturn(false);
        when(user.hasRole(anyString())).thenAnswer(inv -> "eddi-user".equals(inv.getArgument(0)));
        McpHealthTools refused = new McpHealthTools(health, portfolio, json, user, true);
        assertThrows(ForbiddenException.class, () -> refused.listAgentHealth(null, null, null, null));
        verifyNoInteractions(portfolio);
    }
}
