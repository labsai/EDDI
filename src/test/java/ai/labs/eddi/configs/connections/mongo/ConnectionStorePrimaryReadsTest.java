/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.mongo;

import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * The connection lookup decides where a credential may be sent, so it must see
 * the latest save: both the version resolution and the fetch go through the
 * storage's primary-read view, never through the nearest-read storage.
 */
@SuppressWarnings("unchecked")
class ConnectionStorePrimaryReadsTest {

    private static final String ID = "68a1b2c3d4e5f60718293a4b";

    private IResourceStorage<ConnectionConfiguration> nearest;
    private IResourceStorage<ConnectionConfiguration> primary;
    private ConnectionStore store;

    @BeforeEach
    void setUp() throws Exception {
        nearest = mock(IResourceStorage.class);
        primary = mock(IResourceStorage.class);
        when(nearest.primaryReads()).thenReturn(primary);

        var connection = new ConnectionConfiguration();
        connection.setName("jira");
        IResourceStorage.IResource<ConnectionConfiguration> resource = mock(IResourceStorage.IResource.class);
        when(resource.getData()).thenReturn(connection);
        when(primary.getCurrentVersion(ID)).thenReturn(2);
        when(primary.read(ID, 2)).thenReturn(resource);

        IResourceStorageFactory factory = new IResourceStorageFactory() {
            @Override
            public <T> IResourceStorage<T> create(String collectionName, IDocumentBuilder documentBuilder, Class<T> documentType,
                                                  String... indexes) {
                return (IResourceStorage<T>) nearest;
            }
        };

        var descriptorStore = mock(IDocumentDescriptorStore.class);
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.connection/connectionstore/connections/" + ID + "?version=1"));
        when(descriptorStore.readDescriptors(eq(ConnectionStore.RESOURCE_TYPE), any(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(descriptor));

        store = new ConnectionStore(factory, mock(IDocumentBuilder.class), descriptorStore);
    }

    @Test
    @DisplayName("readByName resolves the current version and fetches the connection on the primary")
    void lookupGoesThroughThePrimaryView() throws Exception {
        var found = store.readByName("default", "jira");

        assertNotNull(found);
        assertEquals("jira", found.getName());
        verify(primary).getCurrentVersion(ID);
        verify(primary).read(ID, 2);
        verify(nearest, never()).getCurrentVersion(anyString());
        verify(nearest, never()).read(anyString(), anyInt());
    }
}
