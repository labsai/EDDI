/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.secrets.rest.IRestSecretStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code list_vault_keys}: admin only, a pass-through of the value-free REST
 * listing, and no internal error text in its failures.
 */
@DisplayName("McpVaultTools")
class McpVaultToolsTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private IRestSecretStore secretStore;

    @BeforeEach
    void setUp() {
        secretStore = mock(IRestSecretStore.class);
    }

    private McpVaultTools tools(boolean authEnabled, String... roles) {
        Set<String> granted = Set.of(roles);
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.hasRole(anyString())).thenAnswer(inv -> granted.contains(inv.getArgument(0, String.class)));
        when(identity.getRoles()).thenReturn(granted);
        return new McpVaultTools(secretStore, new JsonSerialization(mapper), identity, authEnabled);
    }

    @Test
    @DisplayName("every role but eddi-admin is refused before the vault is touched")
    void nonAdminRefused() {
        for (String role : List.of("eddi-editor", "eddi-viewer", "eddi-user", "eddi-approver")) {
            var t = tools(true, role);
            assertThrows(ForbiddenException.class, () -> t.listVaultKeys(null), role);
        }
        SecurityIdentity anonymous = mock(SecurityIdentity.class);
        when(anonymous.isAnonymous()).thenReturn(true);
        var t = new McpVaultTools(secretStore, new JsonSerialization(mapper), anonymous, true);
        assertThrows(ForbiddenException.class, () -> t.listVaultKeys("default"));
        verify(secretStore, never()).listSecrets(anyString());
    }

    @Test
    @DisplayName("an admin gets the listing of the default tenant when none is given, and of the trimmed one otherwise")
    void listing() throws Exception {
        when(secretStore.listSecrets("default")).thenReturn(Response.ok(List.of(Map.of("keyName", "openai-key", "allowedAgents", List.of("*"))))
                .build());
        when(secretStore.listSecrets("acme")).thenReturn(Response.ok(List.of()).build());
        var admin = tools(true, "eddi-admin");

        JsonNode rows = mapper.readTree(admin.listVaultKeys(null));
        assertEquals("openai-key", rows.get(0).get("keyName").asText());
        assertEquals("openai-key", mapper.readTree(admin.listVaultKeys("  ")).get(0).get("keyName").asText());
        assertEquals(0, mapper.readTree(admin.listVaultKeys(" acme ")).size());
    }

    @Test
    @DisplayName("without authentication the single operator may list")
    void authOff() throws Exception {
        when(secretStore.listSecrets("default")).thenReturn(Response.ok(List.of()).build());
        assertEquals(0, mapper.readTree(tools(false).listVaultKeys(null)).size());
    }

    @Test
    @DisplayName("a REST refusal keeps its status code; an internal failure does not echo its message")
    void errors() throws Exception {
        when(secretStore.listSecrets("locked")).thenReturn(Response.status(403).entity(Map.of("error", "Vault is locked")).build());
        when(secretStore.listSecrets("broken")).thenThrow(new IllegalStateException("decrypt failed for value sk-live-SECRET at mongodb://vault"));
        var admin = tools(true, "eddi-admin");

        JsonNode refused = mapper.readTree(admin.listVaultKeys("locked"));
        assertEquals("FORBIDDEN", refused.get("errorCode").asText());

        String failure = admin.listVaultKeys("broken");
        JsonNode failed = mapper.readTree(failure);
        assertEquals("INTERNAL", failed.get("errorCode").asText());
        assertFalse(failure.contains("sk-live-SECRET"), failure);
        assertFalse(failure.contains("mongodb://"), failure);
    }

    @Test
    @DisplayName("the vault has exactly one tool, and it neither writes nor reads a value")
    void readOnly() {
        List<String> names = Arrays.stream(McpVaultTools.class.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(Tool.class))
                .map(m -> m.getAnnotation(Tool.class).name()).toList();
        assertEquals(List.of("list_vault_keys"), names);
    }
}
