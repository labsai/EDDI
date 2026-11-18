/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.MailboxGrants;
import ai.labs.eddi.engine.net.SafeSocketConnector;
import ai.labs.eddi.engine.net.SocketPolicyException;
import ai.labs.eddi.integrations.email.ledger.IEmailSuppressionStore;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    @SuppressWarnings("unchecked")
    private MailboxGrants mailboxGrants() {
        MailboxGrants grants = mock(MailboxGrants.class);
        Instance<MailboxGrants> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(grants);
        support.mailboxGrants = instance;
        return grants;
    }

    @Test
    @DisplayName("a MAILBOX connection whose mailbox is not linked saves with email.mailbox-not-linked")
    void mailboxNotLinkedWarning() {
        MailboxGrants grants = mailboxGrants();
        when(resolver.bindingOf("${connection:mailbox}")).thenReturn(Optional.of(Binding.MAILBOX));
        when(grants.isLinked("mailbox", "invoices@contoso.com")).thenReturn(false);

        assertTrue(support.warnings(EmailInboundProcessorTest.integration(Map.of())).contains(EmailChannelSettings.WARNING_MAILBOX_NOT_LINKED));
        assertDoesNotThrow(() -> support.validateAgainstDeployment(EmailInboundProcessorTest.integration(Map.of()), null),
                "an unlinked mailbox is a warning, never a refused save");
    }

    @Test
    @DisplayName("a linked mailbox, or a connection of any other binding, gets no such warning")
    void noWarningWhenLinkedOrNotMailbox() {
        MailboxGrants grants = mailboxGrants();
        when(resolver.bindingOf("${connection:mailbox}")).thenReturn(Optional.of(Binding.MAILBOX));
        when(grants.isLinked("mailbox", "invoices@contoso.com")).thenReturn(true);
        assertFalse(support.warnings(EmailInboundProcessorTest.integration(Map.of())).contains(EmailChannelSettings.WARNING_MAILBOX_NOT_LINKED));

        when(resolver.bindingOf("${connection:mailbox}")).thenReturn(Optional.of(Binding.SERVICE));
        assertFalse(support.warnings(EmailInboundProcessorTest.integration(Map.of())).contains(EmailChannelSettings.WARNING_MAILBOX_NOT_LINKED));
        verify(grants, never()).isLinked("other", "invoices@contoso.com");
    }
}
