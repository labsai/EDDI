/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.integrations.channels.ChannelConnectionCredentials;
import ai.labs.eddi.integrations.channels.ChannelTypeDescriptor;
import ai.labs.eddi.integrations.channels.ChannelTypeRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamsChannelTypeTest {

    static final String APP_ID = "11111111-2222-3333-4444-555555555555";
    static final String TENANT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    static final String OTHER_TENANT = "ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee";

    private final TeamsChannelType type = new TeamsChannelType();

    static Map<String, String> config() {
        Map<String, String> pc = new HashMap<>();
        pc.put("appId", APP_ID);
        pc.put("tenantId", TENANT);
        pc.put("connection", "teams-bot");
        return pc;
    }

    @Test
    void registeredAsConnectionBacked() {
        assertTrue(ChannelTypeRegistry.builtIns().find("teams").isPresent());
        assertTrue(type.connectionBacked());
        assertEquals(Set.of(AuthType.OAUTH2_CLIENT_CREDENTIALS), type.acceptedConnectionAuthTypes());
        assertFalse(type.supportsObserveMode());
    }

    @Test
    void validConfigurationPasses() {
        assertEquals(List.of(), ChannelTypeRegistry.validatePlatformConfig(type, config()));
    }

    @Test
    @DisplayName("a secret in platformConfig is refused: credentials live in the connection")
    void secretRefused() {
        var pc = config();
        pc.put("appPassword", "${vault:teams-secret}");
        assertEquals(1, ChannelTypeRegistry.validatePlatformConfig(type, pc).size());
    }

    @Test
    void appIdAndTenantMustBeGuids() {
        var pc = config();
        pc.put("appId", "not-a-guid");
        pc.put("tenantId", "contoso.onmicrosoft.com");
        assertEquals(2, type.validate(pc).size());
        pc = config();
        pc.remove("tenantId");
        assertEquals(1, type.validate(pc).size());
    }

    @Test
    void listsAndConversationIdsAreValidated() {
        var pc = config();
        pc.put("allowedTenantIds", OTHER_TENANT + ", nope");
        pc.put("hitlApproverAadObjectIds", "x");
        pc.put("conversationId", "19:abc@thread.tacv2;messageid=1");
        pc.put("hitlApprovalConversationId", "a:personal");
        assertEquals(4, type.validate(pc).size());
        pc = config();
        pc.put("allowedTenantIds", OTHER_TENANT);
        pc.put("conversationId", "19:abc_DEF-1.2@thread.tacv2");
        assertEquals(List.of(), type.validate(pc));
    }

    @Test
    @DisplayName("routing: the channel when set, otherwise the application key — never both")
    void routingKeys() {
        assertEquals(List.of(ChannelTypeDescriptor.applicationKey(APP_ID)), type.routingKeys(null, config()));
        var pc = config();
        pc.put("conversationId", "19:abc@thread.tacv2");
        assertEquals(List.of("19:abc@thread.tacv2"), type.routingKeys(null, pc));
    }

    @Test
    void applicationIdIsLowerCase() {
        var pc = config();
        pc.put("appId", APP_ID.toUpperCase());
        assertEquals(APP_ID, type.applicationId(pc).orElseThrow());
    }

    @Test
    @DisplayName("an empty allowedTenantIds means the own tenant only, never every tenant")
    void acceptedTenants() {
        assertEquals(Set.of(TENANT), TeamsChannelType.acceptedTenants(config()));
        var pc = config();
        pc.put("allowedTenantIds", OTHER_TENANT.toUpperCase());
        assertEquals(Set.of(TENANT, OTHER_TENANT), TeamsChannelType.acceptedTenants(pc));
        pc.put("allowedTenantIds", "garbage");
        assertEquals(Set.of(), TeamsChannelType.acceptedTenants(pc));
        pc = config();
        pc.remove("tenantId");
        assertEquals(Set.of(), TeamsChannelType.acceptedTenants(pc));
    }

    @Test
    @DisplayName("the connection must be the app's own: client id, the tenant's token endpoint, the Bot Connector scope")
    void requiredIdentity() {
        var identity = type.requiredConnectionIdentity(config()).orElseThrow();
        assertEquals(APP_ID, identity.clientId());
        assertEquals("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token", identity.tokenUrl());
        assertEquals("https://api.botframework.com/.default", identity.scope());
    }

    @Test
    void userIdentityIsNamespacedAndValidated() {
        assertEquals("teams:" + TENANT + ":" + APP_ID, TeamsUserIdentity.eddiUserId(TENANT.toUpperCase(), APP_ID));
        assertThrows(IllegalArgumentException.class,
                () -> TeamsUserIdentity.eddiUserId(TENANT + ":x", APP_ID));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("save proves possession: a token is obtained for the declared app id")
    void saveProvesIdentity() {
        ConnectionResolver resolver = mock(ConnectionResolver.class);
        Instance<ConnectionResolver> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(resolver);
        var credentials = new ChannelConnectionCredentials(instance);

        assertEquals(List.of(), credentials.validateForSave(type, config()));
        verify(resolver).proveServiceIdentity("${connection:teams-bot}", APP_ID, TeamsChannelType.tokenUrl(TENANT),
                TeamsChannelType.BOT_CONNECTOR_SCOPE);

        doThrow(new ConnectionException(ConnectionException.Reason.GRANT_UNUSABLE, "invalid_client")).when(resolver)
                .proveServiceIdentity(any(), any(), any(), any());
        assertEquals(List.of("platformConfig.connection: invalid_client"), credentials.validateForSave(type, config()));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("at runtime the identity is re-checked without a network call")
    void runtimeIdentityCheck() {
        ConnectionResolver resolver = mock(ConnectionResolver.class);
        Instance<ConnectionResolver> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(resolver);
        var credentials = new ChannelConnectionCredentials(instance);
        var integration = new ChannelIntegrationConfiguration();
        integration.setPlatformConfig(config());

        assertTrue(credentials.holdsRequiredIdentity(type, integration));
        doThrow(new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION, "other app")).when(resolver)
                .requireServiceIdentity(any(), any(), any(), any());
        assertFalse(credentials.holdsRequiredIdentity(type, integration));
        verify(resolver, never()).proveServiceIdentity(any(), any(), any(), any());
    }
}
