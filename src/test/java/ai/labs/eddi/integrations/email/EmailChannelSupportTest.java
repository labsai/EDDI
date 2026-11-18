/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.engine.net.SafeSocketConnector;
import ai.labs.eddi.engine.net.SocketPolicyException;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.IEmailSuppressionStore;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMessageLedger;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The deployment checks of {@link EmailChannelSupport} for conversation mode:
 * the SMTP relay passes the <em>author</em> socket policy, and the connection
 * may log in to the SMTP origin as well as the IMAP one.
 */
class EmailChannelSupportTest {

    private SafeSocketConnector connector;
    private ConnectionResolver resolver;
    private EmailChannelSupport support;
    private IEmailSuppressionStore suppressions;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        connector = mock(SafeSocketConnector.class);
        resolver = mock(ConnectionResolver.class);
        Instance<ConnectionResolver> resolvers = mock(Instance.class);
        when(resolvers.isResolvable()).thenReturn(true);
        when(resolvers.get()).thenReturn(resolver);
        EmailIntegrationDirectory directory = mock(EmailIntegrationDirectory.class);
        when(directory.scan()).thenReturn(List.of());
        support = new EmailChannelSupport(connector, resolvers, directory, mock(EmailPollScheduler.class), mock(EmailAgentToolsInspector.class));
        suppressions = mock(IEmailSuppressionStore.class);
        Instance<IEmailSuppressionStore> store = mock(Instance.class);
        when(store.isResolvable()).thenReturn(true);
        when(store.get()).thenReturn(suppressions);
        support.suppressions = store;
    }

    private static ChannelIntegrationConfiguration conversation() {
        var configuration = EmailInboundProcessorTest.integration(Map.of("mode", "conversation", "smtpHost", "smtp.contoso.com"));
        return configuration;
    }

    @Test
    @DisplayName("conversation mode checks the SMTP host under the author policy and both origins on the connection")
    void conversationChecks() throws Exception {
        assertDoesNotThrow(() -> support.validateAgainstDeployment(conversation(), null));

        verify(connector).resolve("smtp.contoso.com", 587, SafeSocketConnector.Protocol.SMTP);
        verify(connector, never()).resolveOperator(anyString(), anyInt(), any(), any());
        verify(resolver).requireMailLoginTarget("${connection:mailbox}", URI.create("imaps://imap.contoso.com"));
        verify(resolver).requireMailLoginTarget("${connection:mailbox}", URI.create("smtp://smtp.contoso.com:587"));
    }

    @Test
    @DisplayName("an SMTP host the author policy refuses refuses the save")
    void smtpHostRefused() throws Exception {
        when(connector.resolve("smtp.contoso.com", 587, SafeSocketConnector.Protocol.SMTP))
                .thenThrow(new SocketPolicyException(SocketPolicyException.Reason.PRIVATE_ADDRESS, "resolves to a private address"));

        var error = assertThrows(BadRequestException.class, () -> support.validateAgainstDeployment(conversation(), null));
        assertTrue(error.getMessage().contains("platformConfig.smtpHost"), error.getMessage());
    }

    @Test
    @DisplayName("a connection whose allowlist lacks the SMTP origin refuses the save, naming the origin")
    void smtpOriginRequired() {
        doThrow(new ConnectionException(ConnectionException.Reason.TARGET_NOT_ALLOWED, "not on the allowlist")).when(resolver)
                .requireMailLoginTarget(eq("${connection:mailbox}"), eq(URI.create("smtp://smtp.contoso.com:587")));

        var error = assertThrows(BadRequestException.class, () -> support.validateAgainstDeployment(conversation(), null));
        assertTrue(error.getMessage().contains("smtp://smtp.contoso.com:587"), error.getMessage());
    }

    @Test
    @DisplayName("trigger mode never resolves an SMTP host or asks for an SMTP origin")
    void triggerModeNoSmtp() throws Exception {
        support.validateAgainstDeployment(EmailInboundProcessorTest.integration(Map.of()), null);

        verify(connector, never()).resolve(anyString(), anyInt(), eq(SafeSocketConnector.Protocol.SMTP));
        verify(resolver, never()).requireMailLoginTarget(anyString(), eq(URI.create("smtp://null:587")));
    }

    @Test
    @DisplayName("clearing a suppression takes a plain address; deleting the integration drops its list")
    void suppressions() {
        when(suppressions.remove("int-1", "alice@example.com")).thenReturn(true);

        assertTrue(support.clearSuppression("int-1", "Alice@Example.com"));
        assertFalse(support.clearSuppression("int-1", "bob@example.com"));
        assertThrows(BadRequestException.class, () -> support.clearSuppression("int-1", "not an address"));
        support.afterDelete("int-1");
        verify(suppressions).removeAll("int-1");
    }

    private static IEmailMessageLedger.LedgerEntry row(String integrationId, IEmailMessageLedger.Direction direction, String messageId) {
        Instant now = Instant.parse("2026-11-17T10:00:00Z");
        return new IEmailMessageLedger.LedgerEntry(UUID.randomUUID().toString(), integrationId,
                direction == IEmailMessageLedger.Direction.IN ? IEmailMessageLedger.Kind.TRIGGER : IEmailMessageLedger.Kind.REPLY, direction,
                messageId, null, "ref", integrationId + direction + messageId, "alice@example.com", IEmailMessageLedger.State.DONE, 0, null, null,
                null,
                null, now, now, now.plus(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("mail log: one integration's rows only; lookup finds IN and OUT rows, bracketed or bare; blank or overlong ids are refused")
    @SuppressWarnings("unchecked")
    void mailLogIsScopedToTheIntegration() {
        InMemoryEmailMessageLedger ledger = new InMemoryEmailMessageLedger();
        Instance<IEmailMessageLedger> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(ledger);
        support.ledger = instance;
        ledger.insertIfAbsent(row("int-1", IEmailMessageLedger.Direction.IN, "<m1@x.example>"));
        ledger.insertIfAbsent(row("int-1", IEmailMessageLedger.Direction.OUT, "<m1@x.example>"));
        ledger.insertIfAbsent(row("int-2", IEmailMessageLedger.Direction.IN, "<m1@x.example>"));
        ledger.insertIfAbsent(row("int-2", IEmailMessageLedger.Direction.IN, "<m2@x.example>"));

        var page = support.mailLog("int-1", IEmailMessageLedger.LedgerQuery.all(50));
        assertEquals(2, page.rows().size());
        assertTrue(page.rows().stream().allMatch(r -> r.integrationId().equals("int-1")));

        var found = support.lookup("int-1", " m1@x.example ");
        assertEquals(2, found.size());
        assertEquals(IEmailMessageLedger.Direction.IN, found.get(0).direction(), "inbound first");
        assertTrue(found.stream().allMatch(r -> r.integrationId().equals("int-1")));
        assertTrue(support.lookup("int-1", "<m2@x.example>").isEmpty(), "another integration's mail is not found");
        assertThrows(BadRequestException.class, () -> support.lookup("int-1", " "));
        assertThrows(BadRequestException.class, () -> support.lookup("int-1", "<" + "a".repeat(600) + "@x.example>"));
    }
}
