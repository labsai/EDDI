/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.rest;

import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.integrations.email.EmailChannelSupport;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("conversation mode and its keys are refused before anything is checked or stored")
    void conversationModeRefused() throws Exception {
        var error = assertThrows(BadRequestException.class, () -> sut.createChannel(email(Map.of("mode", "conversation"))));
        assertTrue(error.getMessage().contains("not supported yet"));
        assertThrows(BadRequestException.class, () -> sut.createChannel(email(Map.of("replyTemplate", "{output}"))));
        verify(support, never()).validateAgainstDeployment(any(), any());
        verify(channelStore, never()).create(any());
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
