/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.integrations.channels.types.SlackChannelType;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ChannelConnectionCredentialsTest {

    private ConnectionResolver resolver;
    private Instance<ConnectionResolver> instance;
    private ChannelConnectionCredentials credentials;
    private final ChannelTypeDescriptor fake = new FakeChatChannelType();

    @BeforeEach
    void setUp() {
        resolver = mock(ConnectionResolver.class);
        instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(resolver);
        credentials = new ChannelConnectionCredentials(instance);
    }

    @Test
    @DisplayName("the save-time check names the type's origin and accepted auth types")
    void validateForSavePasses() {
        assertEquals(List.of(), credentials.validateForSave(fake, Map.of("connection", "bot")));
        verify(resolver).requireServiceTarget("${connection:bot}", FakeChatChannelType.ORIGIN, Set.of(AuthType.OAUTH2_CLIENT_CREDENTIALS));
    }

    @Test
    @DisplayName("a connection that may not reach the origin is reported with the origin to add")
    void targetNotAllowed() {
        doThrow(new ConnectionException(ConnectionException.Reason.TARGET_NOT_ALLOWED, "Connection 'bot' may not be sent there."))
                .when(resolver).requireServiceTarget(any(), any(), any());
        var errors = credentials.validateForSave(fake, Map.of("connection", "bot"));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("baseUrlAllowlist must contain https://api.fakechat.example"), errors.get(0));
    }

    @Test
    @DisplayName("any other refusal is reported as the resolver phrased it")
    void otherRefusal() {
        doThrow(new ConnectionException(ConnectionException.Reason.NOT_FOUND, "No connection 'bot'."))
                .when(resolver).requireServiceTarget(any(), any(), any());
        assertEquals(List.of("platformConfig.connection: No connection 'bot'."), credentials.validateForSave(fake, Map.of("connection", "bot")));
    }

    @Test
    @DisplayName("a legacy type and a malformed name are not this check's business")
    void notApplicable() {
        assertEquals(List.of(), credentials.validateForSave(new SlackChannelType(), Map.of("connection", "bot")));
        assertEquals(List.of(), credentials.validateForSave(fake, Map.of("connection", "not a name")));
        verifyNoInteractions(resolver);
    }

    @Test
    @DisplayName("without connections in the deployment the save is refused, not waved through")
    void connectionsUnavailable() {
        when(instance.isResolvable()).thenReturn(false);
        var errors = credentials.validateForSave(fake, Map.of("connection", "bot"));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("not available"));
    }

    @Test
    @DisplayName("a send resolves the connection for the real target with no principal, every time")
    void resolvePerSend() {
        var cred = new ResolvedCredential("Authorization", "Bearer t");
        URI target = URI.create("https://smba.example/v3/conversations/1/activities");
        when(resolver.resolve("${connection:bot}", target, null)).thenReturn(cred);
        var integration = new ChannelIntegrationConfiguration();
        integration.setPlatformConfig(Map.of("connection", "${connection:bot}"));
        assertSame(cred, credentials.resolve(integration, target));
        assertSame(cred, credentials.resolve(integration, target));
        verify(resolver, times(2)).resolve(eq("${connection:bot}"), eq(target), eq(null));
    }

    @Test
    @DisplayName("an integration naming no connection cannot send")
    void resolveWithoutConnection() {
        var integration = new ChannelIntegrationConfiguration();
        var ex = assertThrows(ConnectionException.class, () -> credentials.resolve(integration, FakeChatChannelType.ORIGIN));
        assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, ex.getReason());
        verifyNoInteractions(resolver);
    }
}
