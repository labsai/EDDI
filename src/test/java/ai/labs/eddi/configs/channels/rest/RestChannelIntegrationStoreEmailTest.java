/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.rest;

import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.EmailSuppressionView;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.integrations.email.EmailChannelSupport;
import ai.labs.eddi.integrations.email.ledger.IEmailSuppressionStore;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The {@code email} branch of {@link RestChannelIntegrationStore}. */
class RestChannelIntegrationStoreEmailTest {

    private static final String ID = "aabbccddee1122334455aabb";

    private IChannelIntegrationStore channelStore;
    private EmailChannelSupport support;
    private RestChannelIntegrationStore sut;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        channelStore = mock(IChannelIntegrationStore.class);
        IDocumentDescriptorStore descriptors = mock(IDocumentDescriptorStore.class);
        when(descriptors.readDescriptors(eq("ai.labs.channel"), eq(""), eq(0), eq(IDescriptorStore.NO_LIMIT), eq(false))).thenReturn(List.of());
        support = mock(EmailChannelSupport.class);
        Instance<EmailChannelSupport> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(support);
        sut = new RestChannelIntegrationStore(channelStore, descriptors, mock(ResourceAccessGuard.class), instance);
        when(channelStore.create(any())).thenReturn(resourceId(ID, 1));
        when(channelStore.getCurrentResourceId(ID)).thenReturn(resourceId(ID, 1));
    }

    private static IResourceStore.IResourceId resourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    private static ChannelIntegrationConfiguration email(Map<String, String> extra) {
        Map<String, String> pc = new HashMap<>(Map.of("mode", "trigger", "transport", "imap", "connection", "exo", "host", "outlook.office365.com",
                "address", "invoices@contoso.com"));
        pc.putAll(extra);
        var config = new ChannelIntegrationConfiguration();
        config.setName("Invoices");
        config.setChannelType("email");
        config.setPlatformConfig(pc);
        var target = new ChannelTarget();
        target.setName("intake");
        target.setType(ChannelTarget.TargetType.AGENT);
        target.setTargetId("agent-1");
        config.setTargets(List.of(target));
        config.setDefaultTargetName("intake");
        return config;
    }

    @Test
    @DisplayName("email is a registered type: checked against the deployment, scheduled after the write, warnings in a header")
    void createEmail() {
        var config = email(Map.of("requireAuthentication", "false"));
        when(support.warnings(any())).thenReturn(List.of("email.open-trigger", "email.tools-without-approvals"));

        Response response = sut.createChannel(config);

        assertEquals(201, response.getStatus());
        verify(support).validateAgainstDeployment(config, null);
        verify(support).afterSave(ID, config);
        assertEquals("email.open-trigger,email.tools-without-approvals", response.getHeaderString(RestChannelIntegrationStore.WARNINGS_HEADER));
    }

    @Test
    @DisplayName("no warnings, no header")
    void noWarnings() {
        when(support.warnings(any())).thenReturn(List.of());
        assertNull(sut.createChannel(email(Map.of())).getHeaderString(RestChannelIntegrationStore.WARNINGS_HEADER));
    }

    @Test
    @DisplayName("a refusal against the deployment stores nothing and schedules nothing")
    void deploymentRefusal() throws Exception {
        var config = email(Map.of());
        doThrow(new BadRequestException("platformConfig.host: private")).when(support).validateAgainstDeployment(config, null);

        assertThrows(BadRequestException.class, () -> sut.createChannel(config));
        verify(channelStore, never()).create(any());
        verify(support, never()).afterSave(any(), any());
    }

    @Test
    @DisplayName("conversation-mode keys on a trigger integration, and conversation mode without smtpHost, are refused before anything is "
            + "checked or stored")
    void conversationKeysRefusedInTriggerMode() throws Exception {
        var error = assertThrows(BadRequestException.class, () -> sut.createChannel(email(Map.of("replyTemplate", "{output}"))));
        assertTrue(error.getMessage().contains("conversation mode"));
        var noSmtp = assertThrows(BadRequestException.class, () -> sut.createChannel(email(Map.of("mode", "conversation"))));
        assertTrue(noSmtp.getMessage().contains("smtpHost"));
        assertThrows(BadRequestException.class,
                () -> sut.createChannel(email(Map.of("mode", "conversation", "smtpHost", "smtp.office365.com", "requireAuthentication", "false"))));
        verify(support, never()).validateAgainstDeployment(any(), any());
        verify(channelStore, never()).create(any());
    }

    @Test
    @DisplayName("conversation mode is accepted: checked against the deployment, scheduled, and its warnings returned")
    void conversationMode() {
        var config = email(Map.of("mode", "conversation", "smtpHost", "smtp.office365.com", "aiDisclosure", ""));
        when(support.warnings(any())).thenReturn(List.of("email.ai-disclosure-disabled"));

        Response response = sut.createChannel(config);

        assertEquals(201, response.getStatus());
        verify(support).validateAgainstDeployment(config, null);
        verify(support).afterSave(ID, config);
        assertEquals("email.ai-disclosure-disabled", response.getHeaderString(RestChannelIntegrationStore.WARNINGS_HEADER));
    }

    @Test
    @DisplayName("the suppression list is read and cleared through the integration, with the address in the body")
    void suppressions() throws Exception {
        when(channelStore.read(ID, 1)).thenReturn(email(Map.of()));
        when(support.suppressions(ID, 100)).thenReturn(
                List.of(new IEmailSuppressionStore.Suppression(ID, "sha256:ab", "example.com", "bounce:5.1.1",
                        Instant.parse("2026-10-25T10:00:00Z"))));
        when(support.clearSuppression(ID, "alice@example.com")).thenReturn(true);

        var listed = sut.readEmailSuppressions(ID, 100);
        assertEquals(1, listed.size());
        assertEquals("sha256:ab", listed.get(0).addressHash());
        assertEquals("2026-10-25T10:00:00Z", listed.get(0).createdAt());
        assertEquals(204, sut.clearEmailSuppression(ID, new EmailSuppressionView.ClearRequest("alice@example.com")).getStatus());
        assertEquals(404, sut.clearEmailSuppression(ID, new EmailSuppressionView.ClearRequest("bob@example.com")).getStatus());
        assertThrows(BadRequestException.class, () -> sut.clearEmailSuppression(ID, new EmailSuppressionView.ClearRequest(" ")));
    }

    @Test
    @DisplayName("a channel that is not an email integration has no suppression list")
    void suppressionsOnlyForEmail() throws Exception {
        var slack = email(Map.of());
        slack.setChannelType("slack");
        when(channelStore.read(ID, 1)).thenReturn(slack);

        assertThrows(NotFoundException.class, () -> sut.readEmailSuppressions(ID, 10));
        assertThrows(NotFoundException.class, () -> sut.clearEmailSuppression(ID, new EmailSuppressionView.ClearRequest("a@b.example")));
        verify(support, never()).clearSuppression(any(), any());
    }

    @Test
    @DisplayName("observeMode targets are refused for email")
    void observeModeRefused() {
        var config = email(Map.of());
        config.getTargets().get(0).setObserveMode(true);
        var error = assertThrows(BadRequestException.class, () -> sut.createChannel(config));
        assertTrue(error.getMessage().contains("observeMode is not supported for email"));
    }

    @Test
    @DisplayName("update re-checks against the deployment excluding itself and re-syncs the schedule")
    void updateEmail() throws Exception {
        var config = email(Map.of());
        when(channelStore.update(eq(ID), eq(1), any())).thenReturn(2);

        sut.updateChannel(ID, 1, config);

        verify(support).validateAgainstDeployment(config, ID);
        verify(support).afterSave(ID, config);
    }

    @Test
    @DisplayName("deleting an email integration stops its polling")
    void deleteEmail() throws Exception {
        when(channelStore.read(ID, 1)).thenReturn(email(Map.of()));

        sut.deleteChannel(ID, 1, false);

        verify(support).afterDelete(ID);
    }

    private static ChannelTarget second(String name) {
        var target = new ChannelTarget();
        target.setName(name);
        target.setType(ChannelTarget.TargetType.AGENT);
        target.setTargetId("agent-2");
        return target;
    }

    private static String refusal(ChannelIntegrationConfiguration config, RestChannelIntegrationStore sut) {
        return assertThrows(BadRequestException.class, () -> sut.createChannel(config)).getMessage();
    }

    @Test
    @DisplayName("email targets: mentions, addresses and allowedSenders are stored normalised")
    void emailTargetRulesNormalised() {
        var config = email(Map.of("aliases", "finance-agent@contoso.com"));
        var intake = config.getTargets().get(0);
        intake.setMentions(List.of(" Finance ", "AP-team"));
        intake.setAddresses(List.of("Finance-Agent@Contoso.com", "invoices+Urgent@contoso.com"));
        intake.setAllowedSenders(List.of(" *@supplier.com ", "boss@contoso.com"));

        assertEquals(201, sut.createChannel(config).getStatus());

        assertEquals(List.of("finance", "ap-team"), intake.getMentions());
        assertEquals(List.of("finance-agent@contoso.com", "invoices+urgent@contoso.com"), intake.getAddresses());
        assertEquals(List.of("*@supplier.com", "boss@contoso.com"), intake.getAllowedSenders());
    }

    @Test
    @DisplayName("email targets: bad or duplicate mentions, addresses the mailbox does not receive, bad sender patterns are refused")
    void emailTargetRulesRefused() throws Exception {
        var badHandle = email(Map.of());
        badHandle.getTargets().get(0).setMentions(List.of("@finance"));
        assertTrue(refusal(badHandle, sut).contains("mention '@finance' must be"));

        var tooMany = email(Map.of());
        tooMany.getTargets().get(0).setMentions(List.of("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10", "a11"));
        assertTrue(refusal(tooMany, sut).contains("at most 10 mentions"));

        var duplicate = email(Map.of());
        duplicate.getTargets().get(0).setMentions(List.of("finance"));
        var other = second("other");
        other.setMentions(List.of("FINANCE"));
        duplicate.setTargets(List.of(duplicate.getTargets().get(0), other));
        assertTrue(refusal(duplicate, sut).contains("Duplicate mention: 'finance'"));

        var notReceived = email(Map.of());
        notReceived.getTargets().get(0).setAddresses(List.of("finance@contoso.com"));
        assertTrue(refusal(notReceived, sut).contains("must be listed in platformConfig.aliases or be a plus-address"));

        var notAnAddress = email(Map.of());
        notAnAddress.getTargets().get(0).setAddresses(List.of("finance"));
        assertTrue(refusal(notAnAddress, sut).contains("is not a plain email address"));

        var sharedAddress = email(Map.of());
        sharedAddress.getTargets().get(0).setAddresses(List.of("invoices+x@contoso.com"));
        var twin = second("twin");
        twin.setAddresses(List.of("Invoices+X@contoso.com"));
        sharedAddress.setTargets(List.of(sharedAddress.getTargets().get(0), twin));
        assertTrue(refusal(sharedAddress, sut).contains("Duplicate target address"));

        var badSender = email(Map.of());
        badSender.getTargets().get(0).setAllowedSenders(List.of("supplier.com"));
        assertTrue(refusal(badSender, sut).contains("allowedSenders entry 'supplier.com'"));
        verify(channelStore, never()).create(any());
    }

    @Test
    @DisplayName("mentions, addresses and allowedSenders are email-only: refused on another channel type")
    void emailTargetRulesOnlyForEmail() {
        for (int i = 0; i < 3; i++) {
            var slack = email(Map.of());
            slack.setChannelType("slack");
            slack.setPlatformConfig(new HashMap<>(Map.of("channelId", "C1")));
            var target = slack.getTargets().get(0);
            switch (i) {
                case 0 -> target.setMentions(List.of("finance"));
                case 1 -> target.setAddresses(List.of("a@contoso.com"));
                default -> target.setAllowedSenders(List.of("*@contoso.com"));
            }
            assertTrue(refusal(slack, sut).contains("only supported for email integrations"));
        }
        var empty = email(Map.of());
        empty.setChannelType("slack");
        empty.setPlatformConfig(new HashMap<>(Map.of("channelId", "C1")));
        empty.getTargets().get(0).setMentions(List.of());
        assertEquals(201, sut.createChannel(empty).getStatus(), "an empty list says nothing");
    }

    @Test
    @DisplayName("a Slack integration never touches email support")
    void slackUntouched() throws Exception {
        var slack = email(Map.of());
        slack.setChannelType("slack");
        slack.setPlatformConfig(new HashMap<>(Map.of("channelId", "C1")));

        sut.createChannel(slack);

        verify(support, never()).validateAgainstDeployment(any(), isNull());
        verify(support, never()).afterSave(any(), any());
    }
}
