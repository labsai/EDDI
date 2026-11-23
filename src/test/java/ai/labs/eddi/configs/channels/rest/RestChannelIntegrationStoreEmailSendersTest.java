/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.rest;

import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.EmailSenderLists;
import ai.labs.eddi.configs.channels.model.EmailSenderLists.Entries;
import ai.labs.eddi.configs.channels.model.EmailSenderLists.UpdateRequest;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.integrations.email.EmailChannelSupport;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET} and {@code POST /channelstore/channels/{id}/email-senders} (email
 * plan v3 §5.1): a targeted edit that is an ordinary save underneath.
 */
@DisplayName("RestChannelIntegrationStore — email sender lists")
class RestChannelIntegrationStoreEmailSendersTest {

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
        when(channelStore.getCurrentResourceId(ID)).thenReturn(resourceId(3));
        when(channelStore.update(eq(ID), eq(3), any())).thenReturn(4);
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

    private static ChannelIntegrationConfiguration email(Map<String, String> lists) {
        Map<String, String> pc = new HashMap<>(Map.of("mode", "trigger", "transport", "imap", "connection", "exo", "host", "outlook.office365.com",
                "address", "invoices@contoso.com"));
        pc.putAll(lists);
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

    private static UpdateRequest addAllow(int expectedVersion, String... entries) {
        return new UpdateRequest(expectedVersion, new Entries(List.of(entries), List.of()), null, "partner onboarding", null);
    }

    @Test
    @DisplayName("GET answers the current version's lists")
    void readsCurrentLists() throws Exception {
        when(channelStore.read(ID, 3)).thenReturn(email(Map.of("senderAllowlist", "bob@partner.com,*@contoso.com", "senderBlocklist",
                "spam@contoso.com")));

        var view = sut.readEmailSenders(ID);

        assertEquals(3, view.version());
        assertEquals(List.of("bob@partner.com", "*@contoso.com"), view.allow());
        assertEquals(List.of("spam@contoso.com"), view.block());
    }

    @Test
    @DisplayName("a channel that is not an email integration has no sender lists (404)")
    void notEmail() throws Exception {
        var slack = email(Map.of());
        slack.setChannelType("slack");
        when(channelStore.read(ID, 3)).thenReturn(slack);

        assertThrows(NotFoundException.class, () -> sut.readEmailSenders(ID));
        assertThrows(NotFoundException.class, () -> sut.updateEmailSenders(ID, addAllow(3, "alice@partner.com")));
    }

    @Test
    @DisplayName("adding an address writes a new version through the ordinary update path and audits the diff")
    void addWritesNewVersion() throws Exception {
        when(channelStore.read(ID, 3)).thenReturn(email(Map.of("senderAllowlist", "bob@partner.com")));

        Response response = sut.updateEmailSenders(ID, addAllow(3, "Alice@Partner.com"));

        assertEquals(200, response.getStatus());
        var captor = ArgumentCaptor.forClass(ChannelIntegrationConfiguration.class);
        verify(channelStore).update(eq(ID), eq(3), captor.capture());
        assertEquals("bob@partner.com,alice@partner.com", captor.getValue().getPlatformConfig().get("senderAllowlist"));
        // The same deployment checks and follow-up as a save of the whole document.
        verify(support).validateAgainstDeployment(any(), eq(ID));
        verify(support).afterSave(eq(ID), any());
        verify(support).auditSenderListChange(ID, 3, 4, List.of("allow:alice@partner.com"), List.of(), "partner onboarding", false);
        var result = (EmailSenderLists.UpdateResult) response.getEntity();
        assertEquals(4, result.version());
        assertTrue(result.changed());
    }

    @Test
    @DisplayName("a stale expectedVersion is a 409 with the current lists, and nothing is written")
    void staleVersionConflicts() throws Exception {
        when(channelStore.read(ID, 3)).thenReturn(email(Map.of("senderAllowlist", "bob@partner.com")));

        Response response = sut.updateEmailSenders(ID, addAllow(2, "alice@partner.com"));

        assertEquals(409, response.getStatus());
        var current = (EmailSenderLists.View) response.getEntity();
        assertEquals(3, current.version());
        assertEquals(List.of("bob@partner.com"), current.allow());
        verify(channelStore, never()).update(anyString(), anyInt(), any());
        verify(support, never()).auditSenderListChange(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("entries that change nothing are reported, and no version is written")
    void noOpWritesNothing() throws Exception {
        when(channelStore.read(ID, 3)).thenReturn(email(Map.of("senderAllowlist", "bob@partner.com")));

        Response response = sut.updateEmailSenders(ID, addAllow(3, "BOB@partner.com"));

        assertEquals(200, response.getStatus());
        var result = (EmailSenderLists.UpdateResult) response.getEntity();
        assertFalse(result.changed());
        assertEquals(3, result.version());
        assertEquals(List.of("allow:bob@partner.com"), result.unchanged());
        verify(channelStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("removing the last allowlist entry is refused without confirmOpen, and warns with it")
    void emptyingTheAllowlist() throws Exception {
        when(channelStore.read(ID, 3)).thenReturn(email(Map.of("senderAllowlist", "bob@partner.com")));
        var remove = new Entries(List.of("bob@partner.com"), List.of());

        var refused = assertThrows(BadRequestException.class,
                () -> sut.updateEmailSenders(ID, new UpdateRequest(3, null, remove, null, false)));
        assertTrue(refused.getMessage().contains("confirmOpen"));
        verify(channelStore, never()).update(anyString(), anyInt(), any());

        Response response = sut.updateEmailSenders(ID, new UpdateRequest(3, null, remove, "open it up", true));

        assertEquals(200, response.getStatus());
        var captor = ArgumentCaptor.forClass(ChannelIntegrationConfiguration.class);
        verify(channelStore).update(eq(ID), eq(3), captor.capture());
        assertFalse(captor.getValue().getPlatformConfig().containsKey("senderAllowlist"), "an empty list removes its key");
        assertTrue(response.getHeaderString(RestChannelIntegrationStore.WARNINGS_HEADER).contains(EmailSenderLists.WARNING_ALLOWLIST_EMPTIED));
        verify(support).auditSenderListChange(ID, 3, 4, List.of(), List.of("allow:bob@partner.com"), "open it up", true);
    }

    @Test
    @DisplayName("the save's own warnings are passed on next to the operation's")
    void saveWarningsPassedOn() throws Exception {
        when(channelStore.read(ID, 3)).thenReturn(email(Map.of()));
        when(support.warnings(any())).thenReturn(List.of("email.allowlist-broad"));

        Response response = sut.updateEmailSenders(ID, addAllow(3, "*@*.co.uk"));

        var result = (EmailSenderLists.UpdateResult) response.getEntity();
        assertEquals(List.of("email.allowlist-broad"), result.warnings());
        assertEquals("email.allowlist-broad", response.getHeaderString(RestChannelIntegrationStore.WARNINGS_HEADER));
    }

    @Test
    @DisplayName("a full PUT that empties the allowlist is refused without confirmOpen, and with it warns and audits once")
    void fullPutEmptyingTheAllowlist() throws Exception {
        when(channelStore.read(ID, 3)).thenReturn(email(Map.of("senderAllowlist", "bob@partner.com")));

        var refused = assertThrows(BadRequestException.class, () -> sut.updateChannel(ID, 3, email(Map.of())));
        assertTrue(refused.getMessage().contains("confirmOpen"));
        assertThrows(BadRequestException.class, () -> sut.updateChannel(ID, 3, false, email(Map.of())));
        verify(channelStore, never()).update(anyString(), anyInt(), any());

        Response response = sut.updateChannel(ID, 3, true, email(Map.of()));

        assertTrue(response.getStatus() < 300, String.valueOf(response.getStatus()));
        assertTrue(response.getHeaderString(RestChannelIntegrationStore.WARNINGS_HEADER).contains(EmailSenderLists.WARNING_ALLOWLIST_EMPTIED));
        verify(support).auditSenderListChange(ID, 3, 4, List.of(), List.of("allow:bob@partner.com"), null, true);
    }

    @Test
    @DisplayName("PUT ?version=0 compares with the current version: emptying the allowlist still needs confirmOpen, and is audited at it")
    void versionZeroIsTheCurrentVersion() throws Exception {
        when(channelStore.read(ID, 3)).thenReturn(email(Map.of("senderAllowlist", "bob@partner.com")));

        var refused = assertThrows(BadRequestException.class, () -> sut.updateChannel(ID, 0, email(Map.of())));
        assertTrue(refused.getMessage().contains("confirmOpen"));
        verify(channelStore, never()).update(anyString(), anyInt(), any());

        sut.updateChannel(ID, 0, true, email(Map.of()));
        verify(support).auditSenderListChange(ID, 3, 4, List.of(), List.of("allow:bob@partner.com"), null, true);
    }

    /**
     * A save on another node lands between the comparison and the write: version 3
     * had no allowlist, the concurrent version 4 adds one. Resolving
     * {@code ?version=0} a second time for the write would empty version 4's
     * allowlist without {@code confirmOpen}, judged against version 3. The write
     * goes to the version that was compared, which the store refuses as stale.
     */
    @Test
    @DisplayName("PUT ?version=0 writes the version it compared: a save landing in between is a 409, not an unchecked overwrite")
    void versionZeroIsResolvedOnce() throws Exception {
        when(channelStore.getCurrentResourceId(ID)).thenReturn(resourceId(3), resourceId(4));
        when(channelStore.read(ID, 3)).thenReturn(email(Map.of()));
        when(channelStore.read(ID, 4)).thenReturn(email(Map.of("senderAllowlist", "bob@partner.com")));
        when(channelStore.update(eq(ID), eq(3), any())).thenThrow(new IResourceStore.ResourceModifiedException("version 3 is not current"));
        when(channelStore.update(eq(ID), eq(4), any())).thenReturn(5);

        assertThrows(IResourceStore.ResourceModifiedException.class, () -> sut.updateChannel(ID, 0, email(Map.of())));

        verify(channelStore).update(eq(ID), eq(3), any());
        verify(channelStore, never()).update(eq(ID), eq(4), any());
        verify(support, never()).auditSenderListChange(anyString(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a save whose stored sender lists cannot be read is refused, not saved unchecked")
    void unreadableBaselineRefuses() throws Exception {
        when(channelStore.read(ID, 3)).thenThrow(new IResourceStore.ResourceStoreException("down"));

        assertThrows(InternalServerErrorException.class, () -> sut.updateChannel(ID, 3, email(Map.of())));
        verify(channelStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("a full PUT that changes the lists is audited; one that leaves them as they were is not")
    void fullPutAudits() throws Exception {
        when(channelStore.read(ID, 3)).thenReturn(email(Map.of("senderAllowlist", "bob@partner.com", "senderBlocklist", "spam@contoso.com")));

        sut.updateChannel(ID, 3, email(Map.of("senderAllowlist", "BOB@partner.com", "senderBlocklist", "spam@contoso.com")));
        verify(support, never()).auditSenderListChange(any(), any(), any(), any(), any(), any(), anyBoolean());

        sut.updateChannel(ID, 3, email(Map.of("senderAllowlist", "bob@partner.com,alice@partner.com")));
        verify(support).auditSenderListChange(ID, 3, 4, List.of("allow:alice@partner.com"), List.of("block:spam@contoso.com"), null, false);
    }

    @Test
    @DisplayName("the targeted edit delegates to the full save: one audit entry, one warning")
    void targetedEditAuditsOnce() throws Exception {
        when(channelStore.read(ID, 3)).thenReturn(email(Map.of("senderAllowlist", "bob@partner.com")));

        Response response = sut.updateEmailSenders(ID,
                new UpdateRequest(3, null, new Entries(List.of("bob@partner.com"), List.of()), "open it up", true));

        verify(support).auditSenderListChange(any(), any(), any(), any(), any(), any(), anyBoolean());
        assertEquals(EmailSenderLists.WARNING_ALLOWLIST_EMPTIED, response.getHeaderString(RestChannelIntegrationStore.WARNINGS_HEADER));
    }

    @Test
    @DisplayName("an invalid entry, or one left on both lists, is a 400 and writes nothing")
    void invalidEntries() throws Exception {
        when(channelStore.read(ID, 3)).thenReturn(email(Map.of("senderBlocklist", "alice@partner.com")));

        assertThrows(BadRequestException.class, () -> sut.updateEmailSenders(ID, addAllow(3, "*@*")));
        assertThrows(BadRequestException.class, () -> sut.updateEmailSenders(ID, addAllow(3, "alice@partner.com")));
        assertThrows(BadRequestException.class, () -> sut.updateEmailSenders(ID, new UpdateRequest(null, null, null, null, null)));
        verify(channelStore, never()).update(anyString(), anyInt(), any());
    }
}
