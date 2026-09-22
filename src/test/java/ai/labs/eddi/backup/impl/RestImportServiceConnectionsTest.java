/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import java.util.Optional;
import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.UpgradeResult;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.IRestConnectionStore;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens to the {@code connections/} directory of an archive.
 * <p>
 * Two rules, each with a test that fails without it: a connection of a name the
 * target already holds is never overwritten, and a document the deployment
 * refuses is a skipped item with its reason rather than a failed import. Both
 * are counted on the response, because a script restoring an agent has no
 * reason to read this instance's log.
 */
@DisplayName("RestImportService — connections")
class RestImportServiceConnectionsTest {

    private static final String AGENT_ORIGIN_ID = "aabb11112222333344445555";
    private static final String NEW_AGENT_ID = "ccdd11112222333344445555";
    private static final String ARCHIVED_CONNECTION_ID = "68a1b2c3d4e5f60718293a4b";
    private static final String CREATED_CONNECTION_ID = "68a1b2c3d4e5f60718293a4c";
    private static final String CREATED_CONNECTION_URI = "eddi://ai.labs.connection/connectionstore/connections/" + CREATED_CONNECTION_ID
            + "?version=1";

    private static final String JIRA_JSON = "{\"name\":\"jira\",\"authType\":\"STATIC\",\"binding\":\"SERVICE\","
            + "\"staticAuth\":{\"headerName\":\"Authorization\",\"valueTemplate\":\"Bearer ${vault:jira-token}\"},"
            + "\"baseUrlAllowlist\":[\"https://api.atlassian.com\"]}";

    private IZipArchive zipArchive;
    private IJsonSerialization jsonSerialization;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IRestConnectionStore restConnectionStore;
    private IConnectionStore connectionStore;
    private IAgentStore agentStore;
    private UpgradeExecutor upgradeExecutor;
    private RestImportService importService;

    private static final String TARGET_AGENT_ID = "eeff11112222333344445555";

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        zipArchive = mock(IZipArchive.class);
        jsonSerialization = mock(IJsonSerialization.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        restConnectionStore = mock(IRestConnectionStore.class);
        connectionStore = mock(IConnectionStore.class);
        var templateSyntaxMigrator = mock(TemplateSyntaxMigrator.class);
        when(templateSyntaxMigrator.migrate(anyString())).thenAnswer(inv -> inv.getArgument(0));

        upgradeExecutor = mock(UpgradeExecutor.class);
        importService = new RestImportService(zipArchive, jsonSerialization, mock(IMigrationManager.class), documentDescriptorStore,
                templateSyntaxMigrator, mock(StructuralMatcher.class), upgradeExecutor, mock(IScheduleStore.class),
                mock(BackupMetrics.class), mock(ResourceAccessGuard.class), mock(SpaceContext.class), mock(RagSourceIngestionService.class),
                true, false, Optional.empty());

        when(jsonSerialization.deserialize(anyString(), eq(AgentConfiguration.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), AgentConfiguration.class));
        when(jsonSerialization.deserialize(anyString(), eq(DocumentDescriptor.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), DocumentDescriptor.class));
        when(jsonSerialization.deserialize(anyString(), eq(ConnectionConfiguration.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), ConnectionConfiguration.class));

        agentStore = mock(IAgentStore.class);
        when(agentStore.create(any())).thenReturn(resourceId(NEW_AGENT_ID, 1));
        when(documentDescriptorStore.getCurrentResourceId(NEW_AGENT_ID)).thenReturn(resourceId(NEW_AGENT_ID, 1));
        var agentDescriptor = new DocumentDescriptor();
        agentDescriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + NEW_AGENT_ID + "?version=1"));
        when(documentDescriptorStore.readDescriptor(NEW_AGENT_ID, 1)).thenReturn(agentDescriptor);

        // The archive: an agent with no workflows, and one connection it references.
        doAnswer(inv -> {
            File dir = inv.getArgument(1);
            assertTrue(dir.mkdirs() || dir.isDirectory());
            Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
            File connectionsDir = new File(dir, "connections");
            assertTrue(connectionsDir.mkdirs());
            Files.writeString(new File(connectionsDir, ARCHIVED_CONNECTION_ID + ".connection.json").toPath(), JIRA_JSON);
            return null;
        }).when(zipArchive).unzip(any(InputStream.class), any(File.class));
    }

    @Test
    @DisplayName("a connection the target does not have is created through the REST gate, with a descriptor")
    void createsAMissingConnection() throws Exception {
        when(connectionStore.idOfName("default", "jira")).thenReturn(null);
        when(restConnectionStore.createConnection(any()))
                .thenReturn(Response.status(201).header("X-Resource-URI", CREATED_CONNECTION_URI).build());

        Response response;
        try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestConnectionStore.class, restConnectionStore, IConnectionStore.class,
                connectionStore)) {
            response = importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
        }

        assertEquals(201, response.getStatus());
        assertNull(response.getHeaderString("X-Connections-Skipped"), "nothing was skipped");
        var created = ArgumentCaptor.forClass(ConnectionConfiguration.class);
        verify(restConnectionStore).createConnection(created.capture());
        assertEquals("jira", created.getValue().getName());
        assertEquals("Bearer ${vault:jira-token}", created.getValue().getStaticAuth().getValueTemplate(),
                "the document travels as references — the target needs the same vault entry, never a value");
        // The store found no descriptor for the new document (the mock knows none),
        // so the import writes one: without it the name never resolves —
        // ${connection:jira} is found through the descriptor index, and no response
        // filter runs on an in-process call.
        verify(documentDescriptorStore).createDescriptor(eq(CREATED_CONNECTION_ID), eq(1), any());
    }

    @Test
    @DisplayName("the descriptor the connection store wrote inside its name lock is not written a second time")
    void doesNotDuplicateTheDescriptorTheStoreWrote() throws Exception {
        // RestConnectionStore writes the descriptor itself, before the name lock is
        // released, so that a concurrent create can see the document. The import must
        // find that one and leave it alone rather than produce a second descriptor for
        // the same document.
        when(connectionStore.idOfName("default", "jira")).thenReturn(null);
        when(restConnectionStore.createConnection(any()))
                .thenReturn(Response.status(201).header("X-Resource-URI", CREATED_CONNECTION_URI).build());
        var written = new DocumentDescriptor();
        written.setResource(URI.create(CREATED_CONNECTION_URI));
        when(documentDescriptorStore.readDescriptor(CREATED_CONNECTION_ID, 1)).thenReturn(written);

        Response response;
        try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestConnectionStore.class, restConnectionStore, IConnectionStore.class,
                connectionStore)) {
            response = importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
        }

        assertEquals(201, response.getStatus());
        verify(documentDescriptorStore, never()).createDescriptor(eq(CREATED_CONNECTION_ID), any(), any());
    }

    @Test
    @DisplayName("a connection whose name the target already holds is skipped, never overwritten, and the skip is counted")
    void skipsAnExistingConnection() throws Exception {
        when(connectionStore.idOfName("default", "jira")).thenReturn("1111222233334444aaaabbbb");

        Response response;
        try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestConnectionStore.class, restConnectionStore, IConnectionStore.class,
                connectionStore)) {
            response = importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
        }

        assertEquals(201, response.getStatus(), "the agent is still imported");
        assertEquals("1", response.getHeaderString("X-Connections-Skipped"));
        verify(restConnectionStore, never()).createConnection(any());
        verify(restConnectionStore, never()).updateConnection(any(), any(), any());
    }

    @Test
    @DisplayName("a connection this deployment refuses is skipped with its reason, and the agent still lands")
    void skipsARefusedConnection() throws Exception {
        // The same 400 the REST boundary answers — here a PER_USER document on a
        // deployment without OIDC — must not become a failed import: the agent is
        // still worth having, and the refusal names what to fix.
        when(connectionStore.idOfName("default", "jira")).thenReturn(null);
        when(restConnectionStore.createConnection(any()))
                .thenThrow(new BadRequestException("A PER_USER connection requires authorization.enabled=true."));

        Response response;
        try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestConnectionStore.class, restConnectionStore, IConnectionStore.class,
                connectionStore)) {
            response = importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
        }

        assertEquals(201, response.getStatus());
        assertEquals("1", response.getHeaderString("X-Connections-Skipped"));
        verify(agentStore).create(any());
        verify(documentDescriptorStore, never()).createDescriptor(eq(CREATED_CONNECTION_ID), any(), any());
    }

    @Test
    @DisplayName("a connection this import created is rolled back when a later resource fails")
    void rollsBackACreatedConnection() throws Exception {
        when(connectionStore.idOfName("default", "jira")).thenReturn(null);
        when(restConnectionStore.createConnection(any()))
                .thenReturn(Response.status(201).header("X-Resource-URI", CREATED_CONNECTION_URI).build());
        when(agentStore.create(any())).thenThrow(new IResourceStore.ResourceStoreException("agent store down"));

        try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestConnectionStore.class, restConnectionStore, IConnectionStore.class,
                connectionStore)) {
            assertThrows(InternalServerErrorException.class,
                    () -> importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null));
        }

        verify(connectionStore).deleteAllPermanently(CREATED_CONNECTION_ID);
        verify(documentDescriptorStore).deleteAllDescriptor(CREATED_CONNECTION_ID);
    }

    @Test
    @DisplayName("a created connection whose response carries no resource URI fails the import, and the connection is found by name and rolled back")
    void missingResourceUriFailsTheImportAndRollsBack() throws Exception {
        // Before: logged, counted as imported, and left outside the transaction — a
        // connection nobody could roll back, on an import that reported success.
        when(connectionStore.idOfName("default", "jira")).thenReturn(null, CREATED_CONNECTION_ID);
        when(restConnectionStore.createConnection(any())).thenReturn(Response.status(201).build());

        try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestConnectionStore.class, restConnectionStore, IConnectionStore.class,
                connectionStore)) {
            assertThrows(InternalServerErrorException.class,
                    () -> importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null));
        }

        verify(connectionStore).deleteAllPermanently(CREATED_CONNECTION_ID);
        verify(agentStore, never()).create(any());
    }

    @Test
    @DisplayName("a created connection whose descriptor cannot be written fails the import and is rolled back")
    void descriptorWriteFailureFailsTheImportAndRollsBack() throws Exception {
        // Without a descriptor ${connection:jira} never resolves, so the agent would
        // land with a reference to a connection that is there and invisible.
        when(connectionStore.idOfName("default", "jira")).thenReturn(null);
        when(restConnectionStore.createConnection(any()))
                .thenReturn(Response.status(201).header("X-Resource-URI", CREATED_CONNECTION_URI).build());
        doThrow(new IllegalStateException("descriptor store down")).when(documentDescriptorStore).createDescriptor(eq(CREATED_CONNECTION_ID),
                eq(1), any());

        try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestConnectionStore.class, restConnectionStore, IConnectionStore.class,
                connectionStore)) {
            assertThrows(InternalServerErrorException.class,
                    () -> importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null));
        }

        verify(connectionStore).deleteAllPermanently(CREATED_CONNECTION_ID);
        verify(documentDescriptorStore).deleteAllDescriptor(CREATED_CONNECTION_ID);
        verify(agentStore, never()).create(any());
    }

    @Test
    @DisplayName("strategy=upgrade creates the archive's missing connections before the upgrade writes configs that reference them")
    void upgradeImportsConnectionsFirst() throws Exception {
        when(connectionStore.idOfName("default", "jira")).thenReturn(null);
        when(restConnectionStore.createConnection(any()))
                .thenReturn(Response.status(201).header("X-Resource-URI", CREATED_CONNECTION_URI).build());
        when(upgradeExecutor.executeUpgrade(any(), eq(TARGET_AGENT_ID), any(), any())).thenReturn(upgraded());

        Response response;
        try (var cdi = stubCdi(IRestConnectionStore.class, restConnectionStore, IConnectionStore.class, connectionStore)) {
            response = importService.importAgent(new ByteArrayInputStream(new byte[0]), "upgrade", null, TARGET_AGENT_ID, null);
        }

        assertTrue(response.getStatus() < 300, "status " + response.getStatus());
        assertNull(response.getHeaderString("X-Connections-Skipped"));
        var order = inOrder(restConnectionStore, upgradeExecutor);
        order.verify(restConnectionStore).createConnection(any());
        order.verify(upgradeExecutor).executeUpgrade(any(), eq(TARGET_AGENT_ID), any(), any());
        verify(connectionStore, never()).deleteAllPermanently(any());
    }

    @Test
    @DisplayName("strategy=upgrade never overwrites an existing connection, and counts the skip")
    void upgradeSkipsAnExistingConnection() throws Exception {
        when(connectionStore.idOfName("default", "jira")).thenReturn("1111222233334444aaaabbbb");
        when(upgradeExecutor.executeUpgrade(any(), eq(TARGET_AGENT_ID), any(), any())).thenReturn(upgraded());

        Response response;
        try (var cdi = stubCdi(IRestConnectionStore.class, restConnectionStore, IConnectionStore.class, connectionStore)) {
            response = importService.importAgent(new ByteArrayInputStream(new byte[0]), "upgrade", null, TARGET_AGENT_ID, null);
        }

        assertEquals("1", response.getHeaderString("X-Connections-Skipped"));
        verify(restConnectionStore, never()).createConnection(any());
        verify(upgradeExecutor).executeUpgrade(any(), eq(TARGET_AGENT_ID), any(), any());
    }

    @Test
    @DisplayName("a connection strategy=upgrade created is removed again when the upgrade fails")
    void upgradeRollsBackACreatedConnection() throws Exception {
        when(connectionStore.idOfName("default", "jira")).thenReturn(null);
        when(restConnectionStore.createConnection(any()))
                .thenReturn(Response.status(201).header("X-Resource-URI", CREATED_CONNECTION_URI).build());
        when(upgradeExecutor.executeUpgrade(any(), eq(TARGET_AGENT_ID), any(), any())).thenThrow(new RuntimeException("Upgrade failed: store down"));

        try (var cdi = stubCdi(IRestConnectionStore.class, restConnectionStore, IConnectionStore.class, connectionStore)) {
            assertThrows(InternalServerErrorException.class,
                    () -> importService.importAgent(new ByteArrayInputStream(new byte[0]), "upgrade", null, TARGET_AGENT_ID, null));
        }

        verify(connectionStore).deleteAllPermanently(CREATED_CONNECTION_ID);
        verify(documentDescriptorStore).deleteAllDescriptor(CREATED_CONNECTION_ID);
    }

    private static UpgradeResult upgraded() {
        return new UpgradeResult(URI.create("eddi://ai.labs.agent/agentstore/agents/" + TARGET_AGENT_ID + "?version=2"), true, 1, 0, 0, List.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static AutoCloseable stubCdi(Object... classThenStore) {
        if (classThenStore.length % 2 != 0) {
            throw new IllegalArgumentException("stubCdi takes (interface, store) PAIRS");
        }
        var cdiMock = mockStatic(CDI.class);
        var cdi = mock(CDI.class);
        cdiMock.when(CDI::current).thenReturn(cdi);
        for (int index = 0; index < classThenStore.length; index += 2) {
            Class storeClass = (Class) classThenStore[index];
            Object store = classThenStore[index + 1];
            var instance = (Instance<Object>) mock(Instance.class);
            when(cdi.select(storeClass)).thenReturn(instance);
            when(instance.get()).thenReturn(store);
        }
        return cdiMock;
    }

    private static IResourceId resourceId(String id, int version) {
        return new IResourceId() {
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

}
