/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.integrations.channels.types.SlackChannelType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ChannelConnectionCredentialsTest {

    private static final String CONNECTION_ID = "aabbccddeeff00112233aabb";

    private ConnectionResolver resolver;
    private Instance<ConnectionResolver> instance;
    private IConnectionStore connectionStore;
    private ResourceAccessGuard accessGuard;
    private SimpleMeterRegistry meters;
    private ChannelConnectionCredentials credentials;
    private final ChannelTypeDescriptor fake = new FakeChatChannelType();

    @BeforeEach
    void setUp() throws Exception {
        resolver = mock(ConnectionResolver.class);
        instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(resolver);
        connectionStore = mock(IConnectionStore.class);
        when(connectionStore.idOfName(anyString(), anyString())).thenReturn(CONNECTION_ID);
        accessGuard = mock(ResourceAccessGuard.class);
        meters = new SimpleMeterRegistry();
        var registry = new ChannelTypeRegistry(List.of(new SlackChannelType(), new FakeChatChannelType(),
                new FakeChatChannelType.OwnedByClientId()), meters);
        credentials = new ChannelConnectionCredentials(instance, registry, connectionStore, accessGuard);
    }

    private double rejected(String type, String reason) {
        var counter = meters.find("eddi_channel_config_rejected_total").tags("type", type, "reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Nested
    @DisplayName("Save time")
    class SaveTime {

        @Test
        @DisplayName("the check names the type's origin and accepted auth types, after the caller's use access")
        void passes() {
            assertEquals(List.of(), credentials.validateForSave(fake, Map.of("connection", "bot")));
            verify(accessGuard).requireUseAccess(CONNECTION_ID, "connection");
            verify(resolver).requireServiceTarget("${connection:bot}", FakeChatChannelType.ORIGIN, Set.of(AuthType.OAUTH2_CLIENT_CREDENTIALS));
        }

        @Test
        @DisplayName("a connection the caller may not use is refused, without telling them anything about it")
        void forbidden() {
            doThrow(new ForbiddenException("no")).when(accessGuard).requireUseAccess(CONNECTION_ID, "connection");
            var errors = credentials.validateForSave(fake, Map.of("connection", "bot"));
            assertEquals(1, errors.size());
            assertTrue(errors.get(0).contains("no usable connection 'bot'"), errors.get(0));
            verify(resolver, never()).requireServiceTarget(any(), any(), any());
            assertEquals(1, rejected("fakechat", "connection_not_usable"));
        }

        @Test
        @DisplayName("a missing connection gets exactly the same answer as a forbidden one")
        void missingIsIndistinguishable() throws Exception {
            doThrow(new ForbiddenException("no")).when(accessGuard).requireUseAccess(CONNECTION_ID, "connection");
            var forbidden = credentials.validateForSave(fake, Map.of("connection", "bot"));
            when(connectionStore.idOfName("default", "bot")).thenReturn(null);
            assertEquals(forbidden, credentials.validateForSave(fake, Map.of("connection", "bot")));
        }

        @Test
        @DisplayName("a store failure during the lookup asks for a retry instead of guessing")
        void lookupFailure() throws Exception {
            when(connectionStore.idOfName(anyString(), anyString())).thenThrow(new IResourceStore.ResourceStoreException("down"));
            var errors = credentials.validateForSave(fake, Map.of("connection", "bot"));
            assertTrue(errors.get(0).contains("retry"), errors.get(0));
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
        @DisplayName("any other refusal is reported as the resolver phrased it, to a caller who may use the connection")
        void otherRefusal() {
            doThrow(new ConnectionException(ConnectionException.Reason.UNSUPPORTED_PLACEMENT, "Connection 'bot' is bound PER_USER."))
                    .when(resolver).requireServiceTarget(any(), any(), any());
            assertEquals(List.of("platformConfig.connection: Connection 'bot' is bound PER_USER."),
                    credentials.validateForSave(fake, Map.of("connection", "bot")));
        }

        @Test
        @DisplayName("a workspace's connection is looked up in its tenant")
        void tenantQualified() throws Exception {
            assertEquals(List.of(), credentials.validateForSave(fake, Map.of("connection", "space-a/bot")));
            verify(connectionStore).idOfName("space-a", "bot");
            verify(resolver).requireServiceTarget(eq("${connection:space-a/bot}"), any(), any());
        }

        @Test
        @DisplayName("a type whose application id is its client id refuses a connection of another application")
        void applicationNotOwned() {
            var owned = new FakeChatChannelType.OwnedByClientId();
            when(resolver.oauthClientIdOf("${connection:bot}")).thenReturn(Optional.of("someone-elses-app"));
            var errors = credentials.validateForSave(owned, Map.of("connection", "bot", "appId", "app-1"));
            assertEquals(1, errors.size());
            assertTrue(errors.get(0).contains("must be the OAuth client id"), errors.get(0));

            when(resolver.oauthClientIdOf("${connection:bot}")).thenReturn(Optional.empty());
            assertEquals(1, credentials.validateForSave(owned, Map.of("connection", "bot", "appId", "app-1")).size(),
                    "a connection with no client id proves nothing");

            when(resolver.oauthClientIdOf("${connection:bot}")).thenReturn(Optional.of("app-1"));
            assertEquals(List.of(), credentials.validateForSave(owned, Map.of("connection", "bot", "appId", "app-1")));
        }

        @Test
        @DisplayName("claiming an application's direct messages needs edit access to the connection, not only use")
        void applicationClaimNeedsEdit() {
            doThrow(new ForbiddenException("no")).when(accessGuard).requireAccess(CONNECTION_ID, AccessLevel.EDIT, "connection");
            var errors = credentials.validateForSave(fake, Map.of("connection", "bot", "appId", "app-1"));
            assertEquals(1, errors.size());
            assertTrue(errors.get(0).contains("needs edit access to connection 'bot'"), errors.get(0));
            assertEquals(1, rejected("fakechat", "application_needs_edit"));
            verify(resolver, never()).requireServiceTarget(any(), any(), any());

            assertEquals(List.of(), credentials.validateForSave(fake, Map.of("connection", "bot", "appId", "app-1", "channelId", "C1")),
                    "a channel-bound integration claims no DMs, so use access is enough");
        }

        @Test
        @DisplayName("a store failure while reading the client id is reported, not thrown past the save")
        void clientIdLookupFailure() {
            var owned = new FakeChatChannelType.OwnedByClientId();
            when(resolver.oauthClientIdOf("${connection:bot}"))
                    .thenThrow(new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION, "store down"));
            var errors = credentials.validateForSave(owned, Map.of("connection", "bot", "appId", "app-1"));
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.get(0).startsWith("platformConfig.connection:"), errors.get(0));
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
    }

    @Nested
    @DisplayName("Connection names")
    class Names {

        @Test
        @DisplayName("bare, tenant-qualified and wrapped names parse; anything else does not")
        void parse() {
            assertEquals(Optional.of(new ConnectionReference("default", "bot")),
                    ChannelConnectionCredentials.connectionOf(Map.of("connection", "bot")));
            assertEquals(Optional.of(new ConnectionReference("space-a", "bot")),
                    ChannelConnectionCredentials.connectionOf(Map.of("connection", "${connection:space-a/bot}")));
            assertTrue(ChannelConnectionCredentials.connectionOf(Map.of("connection", "a/b/c")).isEmpty());
            assertTrue(ChannelConnectionCredentials.connectionOf(Map.of("connection", "/bot")).isEmpty());
            assertTrue(ChannelConnectionCredentials.connectionOf(Map.of("connection", "x ${connection:bot}")).isEmpty());
            assertTrue(ChannelConnectionCredentials.connectionOf(Map.of()).isEmpty());
        }
    }

    @Nested
    @DisplayName("Send time")
    class SendTime {

        @Test
        @DisplayName("a send re-checks binding and auth type for the real target, with no principal, every time")
        void resolvePerSend() {
            var cred = new ResolvedCredential("Authorization", "Bearer t");
            URI target = URI.create("https://smba.example/v3/conversations/1/activities");
            when(resolver.resolveService("${connection:bot}", target, Set.of(AuthType.OAUTH2_CLIENT_CREDENTIALS))).thenReturn(cred);
            var integration = new ChannelIntegrationConfiguration();
            integration.setChannelType(FakeChatChannelType.ID);
            integration.setPlatformConfig(Map.of("connection", "${connection:bot}"));
            assertSame(cred, credentials.resolve(integration, target));
            assertSame(cred, credentials.resolve(integration, target));
            verify(resolver, times(2)).resolveService(eq("${connection:bot}"), eq(target), eq(Set.of(AuthType.OAUTH2_CLIENT_CREDENTIALS)));
            verify(resolver, never()).resolve(any(), any(), any());
        }

        @Test
        @DisplayName("a refused send is counted and rethrown")
        void refusedSendCounted() {
            when(resolver.resolveService(any(), any(), any()))
                    .thenThrow(new ConnectionException(ConnectionException.Reason.UNSUPPORTED_PLACEMENT, "now PER_USER"));
            var integration = new ChannelIntegrationConfiguration();
            integration.setChannelType(FakeChatChannelType.ID);
            integration.setPlatformConfig(Map.of("connection", "bot"));
            assertThrows(ConnectionException.class, () -> credentials.resolve(integration, FakeChatChannelType.ORIGIN));
            var counter = meters.find("eddi_channel_credential_refused_total").tags("type", "fakechat", "reason", "unsupported_placement")
                    .counter();
            assertEquals(1, counter == null ? 0 : counter.count());
        }

        @Test
        @DisplayName("an integration of a type that is not connection-backed cannot send through a connection")
        void legacyTypeRefused() {
            var integration = new ChannelIntegrationConfiguration();
            integration.setChannelType("slack");
            integration.setPlatformConfig(Map.of("connection", "bot"));
            assertThrows(ConnectionException.class, () -> credentials.resolve(integration, FakeChatChannelType.ORIGIN));
            verifyNoInteractions(resolver);
        }

        @Test
        @DisplayName("an integration naming no connection cannot send")
        void resolveWithoutConnection() {
            var integration = new ChannelIntegrationConfiguration();
            integration.setChannelType(FakeChatChannelType.ID);
            var ex = assertThrows(ConnectionException.class, () -> credentials.resolve(integration, FakeChatChannelType.ORIGIN));
            assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, ex.getReason());
            verifyNoInteractions(resolver);
            assertEquals(1, refusedInvalidConfiguration());
        }

        @Test
        @DisplayName("a send without connections in the deployment is refused and counted")
        void resolveWithoutResolver() {
            when(instance.isResolvable()).thenReturn(false);
            var integration = new ChannelIntegrationConfiguration();
            integration.setChannelType(FakeChatChannelType.ID);
            integration.setPlatformConfig(Map.of("connection", "bot"));
            assertThrows(ConnectionException.class, () -> credentials.resolve(integration, FakeChatChannelType.ORIGIN));
            verifyNoInteractions(resolver);
            assertEquals(1, refusedInvalidConfiguration());
        }

        private double refusedInvalidConfiguration() {
            var counter = meters.find("eddi_channel_credential_refused_total").tags("type", "fakechat", "reason", "invalid_configuration")
                    .counter();
            return counter == null ? 0 : counter.count();
        }
    }
}
