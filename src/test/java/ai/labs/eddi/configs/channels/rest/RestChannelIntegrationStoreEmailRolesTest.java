/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.rest;

import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.EmailControl;
import ai.labs.eddi.configs.channels.model.EmailControl.RolePolicy;
import ai.labs.eddi.configs.channels.model.EmailControl.SenderRole;
import ai.labs.eddi.configs.channels.model.EmailSenderRoles;
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
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET} and {@code PUT /channelstore/channels/{id}/email-roles} (email
 * plan v3 §9), and the sender roles on a whole-document save.
 */
@DisplayName("RestChannelIntegrationStore — email sender roles")
class RestChannelIntegrationStoreEmailRolesTest {

    private static final String ID = "aabbccddee1122334455aabb";

    private IChannelIntegrationStore channelStore;
    private EmailChannelSupport support;
    private RestChannelIntegrationStore sut;
    private EmailControl stored;

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
        when(channelStore.getCurrentResourceId(ID)).thenReturn(resourceId(3));
        when(channelStore.update(eq(ID), eq(3), any())).thenReturn(4);
        stored = new EmailControl(List.of(new SenderRole("customer", List.of("*@customer-a.com"))),
                Map.of("customer", new RolePolicy(List.of("support"), List.of("kb_search"), null, null, null)));
        // A fresh copy per read, as a real store answers.
        when(channelStore.read(ID, 3)).thenAnswer(invocation -> email(stored));
    }

    private static IResourceStore.IResourceId resourceId(int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return ID;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    private static ChannelIntegrationConfiguration email(EmailControl control) {
        Map<String, String> pc = new HashMap<>(Map.of("mode", "trigger", "transport", "imap", "connection", "exo", "host", "outlook.office365.com",
                "address", "support@contoso.com", "maxTurnsPerSenderPerDay", "20"));
        var config = new ChannelIntegrationConfiguration();
        config.setName("Support");
        config.setChannelType("email");
        config.setPlatformConfig(pc);
        var support = new ChannelTarget();
        support.setName("support");
        support.setTargetId("agent-1");
        var finance = new ChannelTarget();
        finance.setName("finance");
        finance.setTargetId("agent-2");
        config.setTargets(List.of(support, finance));
        config.setDefaultTargetName("support");
        config.setEmailControl(control == null ? null : new EmailControl(control.getSenderRoles(), control.getRolePolicies()));
        return config;
    }

    @Test
    @DisplayName("GET answers the current version's roles")
    void readsRoles() {
        var view = sut.readEmailRoles(ID);

        assertEquals(3, view.version());
        assertEquals("customer", view.senderRoles().getFirst().role());
        assertEquals(List.of("kb_search"), view.rolePolicies().get("customer").tools());
    }

    @Test
    @DisplayName("PUT replaces the roles as a new version and audits what changed")
    void replaceWritesNewVersion() throws Exception {
        var request = new EmailSenderRoles.UpdateRequest(3,
                List.of(new SenderRole("exec", List.of("CFO@contoso.com")), new SenderRole("customer", List.of("*@customer-a.com"))),
                Map.of("exec", new RolePolicy(List.of("finance"), null, null, null, null), "customer",
                        new RolePolicy(List.of("support"), List.of("kb_search"), null, null, null)),
                "CFO may reach finance");

        Response response = sut.updateEmailRoles(ID, request);

        assertEquals(200, response.getStatus());
        var captor = ArgumentCaptor.forClass(ChannelIntegrationConfiguration.class);
        verify(channelStore).update(eq(ID), eq(3), captor.capture());
        assertEquals(List.of("cfo@contoso.com"), captor.getValue().getEmailControl().getSenderRoles().getFirst().senders());
        verify(support).auditSenderRolesChange(ID, 3, 4, List.of("role+:exec", "policy+:exec"), "CFO may reach finance");
        assertEquals(4, ((EmailSenderRoles.UpdateResult) response.getEntity()).version());
    }

    @Test
    @DisplayName("a stale expectedVersion is a 409 with the current roles, and nothing is written")
    void staleConflicts() throws Exception {
        Response response = sut.updateEmailRoles(ID, new EmailSenderRoles.UpdateRequest(2, List.of(), Map.of(), null));

        assertEquals(409, response.getStatus());
        assertEquals(3, ((EmailSenderRoles.View) response.getEntity()).version());
        verify(channelStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("a policy that would widen the integration's cap is refused, and nothing is written")
    void wideningRefused() throws Exception {
        var request = new EmailSenderRoles.UpdateRequest(3, List.of(new SenderRole("customer", List.of("*@customer-a.com"))),
                Map.of("customer", new RolePolicy(null, null, null, null, 500)), null);

        var refused = assertThrows(BadRequestException.class, () -> sut.updateEmailRoles(ID, request));

        assertTrue(refused.getMessage().contains("can only lower it"), refused.getMessage());
        verify(channelStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("removing every role through the targeted edit is allowed and audited")
    void removeAllExplicitly() throws Exception {
        Response response = sut.updateEmailRoles(ID, new EmailSenderRoles.UpdateRequest(3, List.of(), Map.of(), "back to defaults"));

        assertEquals(200, response.getStatus());
        var captor = ArgumentCaptor.forClass(ChannelIntegrationConfiguration.class);
        verify(channelStore).update(eq(ID), eq(3), captor.capture());
        assertNull(captor.getValue().getEmailControl());
        verify(support).auditSenderRolesChange(ID, 3, 4, List.of("role-:customer", "policy-:customer"), "back to defaults");
    }

    @Test
    @DisplayName("a whole-document save that leaves emailControl out does not silently drop the roles")
    void wholeSaveCannotDropRoles() throws Exception {
        var refused = assertThrows(BadRequestException.class, () -> sut.updateChannel(ID, 3, null, email(null)));

        assertTrue(refused.getMessage().contains("emailControl"), refused.getMessage());
        verify(channelStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("a whole-document save is validated like the targeted edit: a widening policy is refused")
    void wholeSaveValidates() {
        var widening = email(new EmailControl(List.of(new SenderRole("customer", List.of("*@customer-a.com"))),
                Map.of("customer", new RolePolicy(List.of("nowhere"), null, null, null, null))));

        assertThrows(BadRequestException.class, () -> sut.updateChannel(ID, 3, null, widening));
    }

    @Test
    @DisplayName("emailControl on a non-email integration is refused")
    void nonEmailRefused() {
        var slack = email(stored);
        slack.setChannelType("slack");
        slack.setPlatformConfig(new HashMap<>(Map.of("channelId", "C123")));

        var refused = assertThrows(BadRequestException.class, () -> sut.createChannel(slack));
        assertTrue(refused.getMessage().contains("emailControl"), refused.getMessage());
    }
}
