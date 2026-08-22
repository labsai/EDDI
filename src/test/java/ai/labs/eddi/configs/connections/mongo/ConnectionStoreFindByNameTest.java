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
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The named lookup {@code ${connection:…}} resolves through.
 * <p>
 * The subject here is a distinction the scan has to make and that a single
 * {@code catch (Exception)} would erase. A descriptor whose resource is gone is
 * a stale index entry the scan must step over; a store that could not answer is
 * an outage. Reporting the second as "no connection by that name" is the
 * dangerous half — {@code ConnectionRegistry} caches the absence, and one
 * transient read error then fails every turn that uses a live connection until
 * the cache expires, long after the database recovered.
 */
class ConnectionStoreFindByNameTest {

    // Named for what it is rather than for the connection it belongs to: a
    // provider name sitting next to a hex blob is the shape every credential
    // scanner looks for, and this is a document id.
    private static final String CONNECTION_ID = "68a1b2c3d4e5f60718293a4b";
    private static final String OTHER_TENANT_ID = "68a1b2c3d4e5f60718293a4c";
    private static final String DANGLING_ID = "68a1b2c3d4e5f60718293a4d";

    /**
     * The document storage is never reached: every per-resource read in these tests
     * goes through the seam {@link ProbeConnectionStore} provides, which is the
     * only place the two failure modes can be told apart.
     */
    private static final IResourceStorageFactory NO_STORAGE = new IResourceStorageFactory() {
        @Override
        public <T> IResourceStorage<T> create(String collectionName, IDocumentBuilder documentBuilder, Class<T> documentType, String... indexes) {
            return null;
        }
    };

    private IDocumentDescriptorStore descriptorStore;
    private ProbeConnectionStore store;

    @BeforeEach
    void setUp() {
        descriptorStore = mock(IDocumentDescriptorStore.class);
        store = new ProbeConnectionStore(descriptorStore);
    }

    /** Puts these ids in the descriptor index, in this order. */
    private void indexed(String... ids) throws Exception {
        var descriptors = new ArrayList<DocumentDescriptor>();
        for (String id : ids) {
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.connection/connectionstore/connections/" + id + "?version=1"));
            descriptors.add(descriptor);
        }
        when(descriptorStore.readDescriptors(eq(ConnectionStore.RESOURCE_TYPE), any(), anyInt(), anyInt(), anyBoolean())).thenReturn(descriptors);
    }

    private static ConnectionConfiguration connection(String name, String tenantId) {
        var connection = new ConnectionConfiguration();
        connection.setName(name);
        connection.setTenantId(tenantId);
        return connection;
    }

    @Test
    @DisplayName("a store failure during the scan reaches the caller instead of reading as 'no such connection'")
    void propagatesAStoreFailureRatherThanReportingAbsence() throws Exception {
        indexed(CONNECTION_ID);
        store.cannotBeRead(CONNECTION_ID);

        var error = assertThrows(IResourceStore.ResourceStoreException.class, () -> store.readByName("default", "jira"));

        // The original failure, not a substitute: a caller that sees this can retry,
        // and the registry deliberately caches nothing it never got an answer for.
        assertTrue(error.getMessage().contains("the database blinked"),
                "the store failure must reach the caller unchanged, or the outage is indistinguishable from an absence: " + error.getMessage());
    }

    @Test
    @DisplayName("idOfName propagates a store failure too — a swallowed one would let a duplicate name through")
    void idOfNamePropagatesAStoreFailure() throws Exception {
        // RestConnectionStore reads a null here as "the name is free". If the scan
        // swallowed an outage, a second connection called "jira" would be created
        // during the outage and resolution would then depend on scan order — one
        // system's credential going to another system's allowlisted origin.
        indexed(CONNECTION_ID);
        store.cannotBeRead(CONNECTION_ID);

        var error = assertThrows(IResourceStore.ResourceStoreException.class, () -> store.idOfName("default", "jira"));

        assertTrue(error.getMessage().contains("the database blinked"), error.getMessage());
    }

    @Test
    @DisplayName("a descriptor pointing at a deleted resource is stepped over, and the scan carries on")
    void skipsADanglingDescriptorAndKeepsScanning() throws Exception {
        // The other half of the same rule, and the reason it cannot be written as one
        // blanket catch: one stale index entry must not hide every connection filed
        // behind it.
        indexed(DANGLING_ID, CONNECTION_ID);
        store.holding(CONNECTION_ID, connection("jira", "default"));

        assertNotNull(store.readByName("default", "jira"), "a stale index entry must not make the connections after it unreachable");
        assertEquals(CONNECTION_ID, store.idOfName("default", "jira"));
    }

    @Test
    @DisplayName("the match is the (tenant, name) pair, not the name alone")
    void scopesTheMatchToTheTenant() throws Exception {
        // Grants are filed under both halves, so a lookup that matched on the name
        // alone would hand one tenant's connection — and its stored tokens — to
        // another's reference. The other tenant is indexed FIRST so a name-only match
        // returns it and this fails.
        indexed(OTHER_TENANT_ID, CONNECTION_ID);
        store.holding(OTHER_TENANT_ID, connection("jira", "acme"));
        store.holding(CONNECTION_ID, connection("jira", "default"));

        assertEquals(CONNECTION_ID, store.idOfName("default", "jira"), "the default tenant's own 'jira' must be the one that resolves");
        assertEquals(OTHER_TENANT_ID, store.idOfName("acme", "jira"), "and acme's 'jira' must be acme's");
        assertNull(store.idOfName("globex", "jira"), "a tenant holding no connection of that name must get nothing, not somebody else's");
    }

    @Test
    @DisplayName("an unqualified lookup resolves against the default tenant")
    void treatsABlankTenantAsTheDefault() throws Exception {
        // ${connection:jira} carries no tenant, and the stored document may leave
        // tenantId unset. Both sides go through effectiveTenant, so two spellings of
        // the default must not file one connection under two tenants.
        indexed(CONNECTION_ID);
        store.holding(CONNECTION_ID, connection("jira", null));

        assertEquals(CONNECTION_ID, store.idOfName(null, "jira"));
        assertEquals(CONNECTION_ID, store.idOfName("default", "jira"));
    }

    /**
     * A {@link ConnectionStore} whose per-resource read is a seam.
     * <p>
     * Everything else — the descriptor scan, the id and version parsing, the
     * tenant-and-name match — is the production code under test.
     */
    private static final class ProbeConnectionStore extends ConnectionStore {

        private final Map<String, ConnectionConfiguration> documents = new LinkedHashMap<>();
        private final Set<String> unreadable = new LinkedHashSet<>();

        private ProbeConnectionStore(IDocumentDescriptorStore descriptorStore) {
            super(NO_STORAGE, mock(IDocumentBuilder.class), descriptorStore);
        }

        private void holding(String id, ConnectionConfiguration connection) {
            documents.put(id, connection);
        }

        private void cannotBeRead(String id) {
            unreadable.add(id);
        }

        @Override
        public ConnectionConfiguration read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
            if (unreadable.contains(id)) {
                throw new ResourceStoreException("the database blinked");
            }
            ConnectionConfiguration document = documents.get(id);
            if (document == null) {
                throw new ResourceNotFoundException("Resource not found. (id=" + id + ", version=" + version + ")");
            }
            return document;
        }
    }
}
