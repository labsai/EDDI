/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.snippets.IPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import ai.labs.eddi.utils.FileUtilities;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * An archive carries the connections its configs reference, and nothing else
 * about connections.
 * <p>
 * The scratch tree is deleted once the ZIP exists, so what was written is
 * observed from inside the {@code createZip} stub, while the files are still
 * there.
 */
@DisplayName("RestExportService — connections")
class RestExportServiceConnectionsTest {

    private static final String AGENT_ID = "d13connectionsexportagent";
    private static final String WORKFLOW_ID = "eeee11112222333344445555";
    private static final String HTTPCALLS_ID = "aaaa11112222333344445555";
    private static final String CONNECTION_ID = "68a1b2c3d4e5f60718293a4b";

    private IConnectionStore connectionStore;
    private IZipArchive zipArchive;
    private RestExportService exportService;
    private String httpCallsJson;

    private Path exportRoot;
    private Path archivesRoot;

    @BeforeEach
    void setUp() throws Exception {
        var agentStore = mock(IAgentStore.class);
        var workflowStore = mock(IWorkflowStore.class);
        var httpCallsStore = mock(IApiCallsStore.class);
        var documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        var jsonSerialization = mock(IJsonSerialization.class);
        var scheduleStore = mock(IScheduleStore.class);
        var secretScrubber = mock(SecretScrubber.class);
        connectionStore = mock(IConnectionStore.class);
        zipArchive = mock(IZipArchive.class);

        exportService = new RestExportService(documentDescriptorStore, agentStore, workflowStore, mock(IDictionaryStore.class),
                mock(IRuleSetStore.class), httpCallsStore, mock(ILlmStore.class), mock(IPropertySetterStore.class), mock(IOutputStore.class),
                mock(IMcpCallsStore.class), mock(IRagStore.class), mock(IPromptSnippetStore.class), jsonSerialization, zipArchive, secretScrubber,
                scheduleStore, mock(ResourceAccessGuard.class), mock(BackupMetrics.class), connectionStore);

        Path tmpDir = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp"));
        exportRoot = tmpDir.resolve("export");
        archivesRoot = tmpDir.resolve("archives");

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1")));
        when(agentStore.read(AGENT_ID, 1)).thenReturn(agentConfig);
        when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(new WorkflowConfiguration());
        when(httpCallsStore.read(HTTPCALLS_ID, 1)).thenReturn(new ApiCallsConfiguration());

        var descriptor = new DocumentDescriptor();
        descriptor.setName("Connections Export Agent");
        when(documentDescriptorStore.readDescriptorWithHistory(anyString(), any())).thenReturn(descriptor);

        // The workflow names the httpcalls config; the httpcalls config is where the
        // reference sits, in a header — the one place the scrubber would otherwise
        // have redacted it.
        httpCallsJson = "{\"httpCalls\":[{\"request\":{\"headers\":{\"Authorization\":\"${connection:jira}\"}}}]}";
        when(jsonSerialization.serialize(any())).thenAnswer(invocation -> {
            Object value = invocation.getArgument(0);
            if (value instanceof WorkflowConfiguration) {
                return "{\"workflowSteps\":[{\"config\":{\"uri\":\"eddi://ai.labs.apicalls/apicallstore/apicalls/" + HTTPCALLS_ID
                        + "?version=1\"}}]}";
            }
            if (value instanceof ApiCallsConfiguration) {
                return httpCallsJson;
            }
            if (value instanceof ConnectionConfiguration connection) {
                return "{\"name\":\"" + connection.getName() + "\",\"authType\":\"STATIC\"}";
            }
            return "{}";
        });
        when(secretScrubber.scrubJson(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(scheduleStore.readSchedulesByAgentId(AGENT_ID)).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteRecursively(exportRoot);
        deleteRecursively(archivesRoot);
    }

    @Test
    @DisplayName("a connection an httpcall header references is written into connections/, one nobody references is not")
    void exportsReferencedConnectionOnly() throws Exception {
        var jira = new ConnectionConfiguration();
        jira.setName("jira");
        when(connectionStore.idOfName("default", "jira")).thenReturn(CONNECTION_ID);
        when(connectionStore.readByName("default", "jira")).thenReturn(jira);
        var written = new AtomicReference<String>();
        var connectionsDirEntries = new AtomicReference<List<String>>();
        doAnswer(invocation -> {
            Path source = Paths.get(invocation.getArgument(0, String.class));
            Path connectionsDir = source.resolve("connections");
            try (var entries = Files.list(connectionsDir)) {
                connectionsDirEntries.set(entries.map(path -> path.getFileName().toString()).toList());
            }
            written.set(Files.readString(connectionsDir.resolve(CONNECTION_ID + ".connection.json")));
            return null;
        }).when(zipArchive).createZip(anyString(), anyString(), any());

        Response response = exportService.exportAgent(AGENT_ID, 1, null, null, null);

        assertEquals(200, response.getStatus());
        assertNotNull(written.get(), "the referenced connection must be in the archive");
        assertTrue(written.get().contains("\"name\":\"jira\""), written.get());
        assertEquals(List.of(CONNECTION_ID + ".connection.json"), connectionsDirEntries.get(),
                "only the referenced connection is exported — the store is never swept for the rest");
        verify(connectionStore).readByName("default", "jira");
    }

    @Test
    @DisplayName("an agent that references no connection consults the connection store not at all")
    void exportsNothingWhenNothingIsReferenced() throws Exception {
        httpCallsJson = "{\"httpCalls\":[{\"request\":{\"headers\":{\"Authorization\":\"Bearer ${vault:jira-token}\"}}}]}";
        var connectionsDirExists = new AtomicReference<Boolean>();
        doAnswer(invocation -> {
            Path source = Paths.get(invocation.getArgument(0, String.class));
            connectionsDirExists.set(Files.exists(source.resolve("connections")));
            return null;
        }).when(zipArchive).createZip(anyString(), anyString(), any());

        exportService.exportAgent(AGENT_ID, 1, null, null, null);

        assertFalse(connectionsDirExists.get(), "no connections/ directory for an agent that references none");
        verifyNoInteractions(connectionStore);
    }

    @Test
    @DisplayName("a reference to a connection that does not exist is logged, and the export still succeeds")
    void toleratesADanglingReference() throws Exception {
        when(connectionStore.idOfName("default", "jira")).thenReturn(null);
        var connectionsDirExists = new AtomicReference<Boolean>();
        doAnswer(invocation -> {
            Path source = Paths.get(invocation.getArgument(0, String.class));
            connectionsDirExists.set(Files.exists(source.resolve("connections")));
            return null;
        }).when(zipArchive).createZip(anyString(), anyString(), any());

        Response response = exportService.exportAgent(AGENT_ID, 1, null, null, null);

        assertEquals(200, response.getStatus(), "an archive is still worth having; the import side reports the dangling name too");
        assertFalse(connectionsDirExists.get());
    }

    @Test
    @DisplayName("a connection name carrying a line break reaches the log sanitized, so it cannot forge a log entry")
    void logsAReferenceSanitized() throws Exception {
        // The reference grammar stops only at '}', so an author-written config can
        // carry a raw line break inside a connection name — and the export logs the
        // reference it skips.
        httpCallsJson = "{\"httpCalls\":[{\"request\":{\"headers\":{\"Authorization\":\"${connection:acme/ji\nra}\"}}}]}";
        List<String> records = new ArrayList<>();
        var handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record.getMessage() + " " + Arrays.toString(record.getParameters()));
            }

            @Override
            public void flush() {
                // nothing is buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        var logger = Logger.getLogger(RestExportService.class.getName());
        Level previousLevel = logger.getLevel();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        try {
            exportService.exportAgent(AGENT_ID, 1, null, null, null);
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParentHandlers);
        }

        assertTrue(records.stream().anyMatch(record -> record.contains("ji_ra")), "the skipped reference must be logged; saw: " + records);
        assertTrue(records.stream().noneMatch(record -> record.contains("ji\nra")), "a raw line break must never reach the log: " + records);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }
}
