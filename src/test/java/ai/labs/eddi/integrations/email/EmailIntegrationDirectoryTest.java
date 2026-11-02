/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailIntegrationDirectoryTest {

    static final String FIRST = "aabbccddeeff112233445501";
    static final String SECOND = "aabbccddeeff112233445502";

    IChannelIntegrationStore channelStore;
    IDocumentDescriptorStore descriptorStore;
    EmailIntegrationDirectory directory;

    @BeforeEach
    void setUp() throws Exception {
        channelStore = mock(IChannelIntegrationStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        IResourceStore.IResourceId current = mock(IResourceStore.IResourceId.class);
        when(current.getVersion()).thenReturn(1);
        when(channelStore.getCurrentResourceId(anyString())).thenReturn(current);
        when(channelStore.read(anyString(), any())).thenReturn(email());
        when(descriptorStore.readDescriptors(eq("ai.labs.channel"), anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(descriptor("eddi://ai.labs.channel/channelstore/channels/" + FIRST + "?version=1"),
                        descriptor("not a resource uri at all"),
                        descriptor("eddi://ai.labs.channel/channelstore/channels/" + SECOND + "?version=1")));
        directory = new EmailIntegrationDirectory(channelStore, descriptorStore);
    }

    static ChannelIntegrationConfiguration email() {
        var configuration = new ChannelIntegrationConfiguration();
        configuration.setChannelType("email");
        configuration.setPlatformConfig(Map.of("mode", "trigger", "address", "invoices@contoso.com"));
        return configuration;
    }

    static DocumentDescriptor descriptor(String resource) {
        var descriptor = new DocumentDescriptor();
        try {
            descriptor.setResource(URI.create(resource));
        } catch (IllegalArgumentException unparseable) {
            descriptor.setResource(null);
        }
        return descriptor;
    }

    @Test
    @DisplayName("scan lists every email integration and skips a descriptor it cannot parse")
    void skipsUnparseable() {
        assertEquals(List.of(FIRST, SECOND), directory.scan().stream().map(EmailIntegrationDirectory.EmailIntegration::id).toList());
    }

    @Test
    @DisplayName("a store failure on one integration fails the scan instead of leaving that integration out")
    void storeFailurePropagates() throws Exception {
        when(channelStore.read(eq(SECOND), any())).thenThrow(new IResourceStore.ResourceStoreException("connection reset"));

        var thrown = assertThrows(EmailIntegrationDirectory.StoreUnavailableException.class, directory::scan);
        assertInstanceOf(IResourceStore.ResourceStoreException.class, thrown.getCause());
    }

    @Test
    @DisplayName("a failure listing the descriptors fails the scan as a store failure")
    void descriptorListingFailurePropagates() throws Exception {
        when(descriptorStore.readDescriptors(eq("ai.labs.channel"), anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenThrow(new IResourceStore.ResourceStoreException("connection reset"));

        var thrown = assertThrows(EmailIntegrationDirectory.StoreUnavailableException.class, directory::scan);
        assertInstanceOf(IResourceStore.ResourceStoreException.class, thrown.getCause());
    }
}
