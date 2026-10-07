/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.secrets.VaultGrantGate;
import io.quarkiverse.mcp.server.Tool;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP {@code deploy_agent} names the missing grant and the fix — and nothing an
 * LLM can call is able to apply that fix.
 */
@DisplayName("MCP — a refused deploy explains itself, and no tool grants")
class McpDeployGrantFailureTest {

    private static final String AGENT_ID = "0123456789abcdef01234567";

    @Test
    @DisplayName("deploy_agent reports the failure code, the secret names and the fix for a human")
    void deployAgentNamesSecretAndFix() throws Exception {
        var agentAdmin = mock(IRestAgentAdministration.class);
        var json = mock(IJsonSerialization.class);
        when(json.serialize(any())).thenReturn("{}");
        var identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);
        var tools = new McpAdminTools(mock(IRestInterfaceFactory.class), agentAdmin, json, mock(StrictConfigurationParser.class),
                mock(IScheduleStore.class), mock(ScheduleFireExecutor.class), mock(SchedulePollerService.class), identity, false);

        var failure = new VaultGrantGate.GrantCheck(VaultGrantGate.Mode.ENFORCE, List.of("${vault:gemini-api-key}"), true).toFailure(AGENT_ID, 1);
        var body = new LinkedHashMap<String, Object>();
        body.put("status", "ERROR");
        body.put("error", failure.message());
        body.put("failure", failure);
        when(agentAdmin.deployAgent(Environment.production, AGENT_ID, 1, true, true)).thenReturn(Response.ok(body).build());

        tools.deployAgent(AGENT_ID, 1, "production");

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> captor = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(json).serialize(captor.capture());
        Map<String, Object> result = captor.getValue();
        assertEquals("deploy_failed", result.get("action"));
        assertEquals("VAULT_GRANT_MISSING", result.get("failureCode"));
        assertTrue(String.valueOf(result.get("secrets")).contains("gemini-api-key"));
        assertTrue(String.valueOf(result.get("error")).contains("default/gemini-api-key"));
        assertTrue(String.valueOf(result.get("humanActionRequired")).contains("administrator"));
        assertNotNull(result.get("fix"));
    }

    @Test
    @DisplayName("no MCP tool in the server can change a grant")
    void noGrantTool() {
        for (Class<?> type : List.of(McpAdminTools.class, McpSetupTools.class, McpConversationTools.class, McpGroupTools.class,
                McpHitlTools.class, McpMemoryTools.class, McpGdprTools.class, McpDocTools.class)) {
            for (Method method : type.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) {
                    continue;
                }
                String name = tool.name().isEmpty() ? method.getName() : tool.name();
                assertFalse(name.toLowerCase().contains("grant"), type.getSimpleName() + " exposes " + name);
            }
            // Nor a setup tool parameter that could carry the opt-in flag.
            for (Method method : type.getDeclaredMethods()) {
                if (method.getAnnotation(Tool.class) == null) {
                    continue;
                }
                for (var parameter : method.getParameters()) {
                    assertFalse(parameter.getName().toLowerCase().contains("grant"), type.getSimpleName() + "." + method.getName());
                }
            }
        }
    }
}
