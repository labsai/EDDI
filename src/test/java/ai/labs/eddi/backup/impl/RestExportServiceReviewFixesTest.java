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
import ai.labs.eddi.configs.connections.model.StaticAuth;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.parser.IParserStore;
import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.snippets.IPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import ai.labs.eddi.utils.FileUtilities;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.ForbiddenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review fixes on the export path: every document in the archive is scrubbed, a
 * reference the authoring check never saw needs VIEW of its own, a snippet
 * shared for USE only stays behind, and a connection's legacy literal does not
 * leave in a field the generic scrubber cannot name.
 */
@DisplayName("RestExportService — review fixes")
class RestExportServiceReviewFixesTest {

    private static final String AGENT_ID = "aaaa1111222233334444review";
    private static final String WORKFLOW_ID = "eeee11112222333344445555";
    private static final String HTTPCALLS_ID = "aaaa11112222333344445555";
    private static final String FOREIGN_LLM_ID = "ffff11112222333344445555";
    private static final String CONNECTION_ID = "68a1b2c3d4e5f60718293a4b";

    private final ObjectMapper mapper = new ObjectMapper();
    private IAgentStore agentStore;
    private IWorkflowStore workflowStore;
    private ILlmStore llmStore;
    private IPromptSnippetStore snippetStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IConnectionStore connectionStore;
    private ResourceAccessGuard guard;
    private IZipArchive zipArchive;
    private IJsonSerialization jsonSerialization;
    private RestExportService exportService;
    private WorkflowConfiguration workflow;
    private String agentJson = "{\"workflows\":[]}";
    private Path exportRoot;
    private Path archivesRoot;

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
        llmStore = mock(ILlmStore.class);
        snippetStore = mock(IPromptSnippetStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        connectionStore = mock(IConnectionStore.class);
        guard = mock(ResourceAccessGuard.class);
        zipArchive = mock(IZipArchive.class);
        jsonSerialization = mock(IJsonSerialization.class);
        var httpCallsStore = mock(IApiCallsStore.class);
        var scheduleStore = mock(IScheduleStore.class);

        exportService = new RestExportService(documentDescriptorStore, agentStore, workflowStore, mock(IParserStore.class),
                mock(IDictionaryStore.class), mock(IRuleSetStore.class), httpCallsStore, llmStore, mock(IPropertySetterStore.class),
                mock(IOutputStore.class), mock(IMcpCallsStore.class), mock(IRagStore.class), snippetStore, jsonSerialization, zipArchive,
                new SecretScrubber(mapper), scheduleStore, guard, mock(BackupMetrics.class), connectionStore);

        Path tmpDir = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp"));
        exportRoot = tmpDir.resolve("export");
        archivesRoot = tmpDir.resolve("archives");

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1")));
        when(agentStore.read(AGENT_ID, 1)).thenReturn(agentConfig);
        workflow = workflowNaming(Map.of("uri", "eddi://ai.labs.apicalls/apicallstore/apicalls/" + HTTPCALLS_ID + "?version=1"));
        when(workflowStore.read(WORKFLOW_ID, 1)).thenAnswer(i -> workflow);
        when(httpCallsStore.read(HTTPCALLS_ID, 1)).thenReturn(new ApiCallsConfiguration());
        when(documentDescriptorStore.readDescriptorWithHistory(anyString(), any())).thenReturn(new DocumentDescriptor());
        when(scheduleStore.readSchedulesByAgentId(AGENT_ID)).thenReturn(List.of());

        when(jsonSerialization.serialize(any())).thenAnswer(invocation -> {
            Object value = invocation.getArgument(0);
            if (value instanceof AgentConfiguration) {
                return agentJson;
            }
            if (value instanceof WorkflowConfiguration || value instanceof ConnectionConfiguration) {
                return mapper.writeValueAsString(value);
            }
            return "{}";
        });
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(exportRoot);
        deleteRecursively(archivesRoot);
    }

    @Test
    @DisplayName("the agent and workflow documents are scrubbed like every other document")
    void agentAndWorkflowAreScrubbed() throws Exception {
        agentJson = "{\"workflows\":[],\"userMemoryConfig\":{\"dream\":{\"parameters\":{\"apiKey\":\"sk-live-agentkey1234567890\"}}},"
                + "\"channels\":[{\"type\":\"slack\",\"config\":{\"botToken\":\"xoxb-123456789012-abcdefghijkl\"}}]}";
        workflow.getWorkflowSteps().getFirst().getConfig().put("apiKey", "plain-workflow-secret");
        var agentFile = new AtomicReference<String>();
        var workflowFile = new AtomicReference<String>();
        doAnswer(invocation -> {
            Path source = Paths.get(invocation.getArgument(0, String.class));
            agentFile.set(Files.readString(source.resolve(AGENT_ID + ".agent.json")));
            workflowFile.set(Files.readString(source.resolve(WORKFLOW_ID).resolve("1").resolve(WORKFLOW_ID + ".workflow.json")));
            return null;
        }).when(zipArchive).createZip(anyString(), anyString(), any());

        exportService.exportAgent(AGENT_ID, 1, null, null, null);

        assertNotNull(agentFile.get());
        assertFalse(agentFile.get().contains("sk-live-agentkey"), agentFile.get());
        assertFalse(agentFile.get().contains("xoxb-"), agentFile.get());
        assertFalse(workflowFile.get().contains("plain-workflow-secret"), workflowFile.get());
        assertTrue(workflowFile.get().contains(HTTPCALLS_ID), "references survive the scrub: " + workflowFile.get());
    }

    @Test
    @DisplayName("a URI outside the step references the authoring check vetted needs VIEW before it is read")
    void unvettedReferenceNeedsView() throws Exception {
        var config = new LinkedHashMap<String, Object>();
        config.put("uri", "eddi://ai.labs.apicalls/apicallstore/apicalls/" + HTTPCALLS_ID + "?version=1");
        config.put("note", "eddi://ai.labs.llm/llmstore/llms/" + FOREIGN_LLM_ID + "?version=1");
        workflow = workflowNaming(config);
        when(guard.requireAccess(eq(FOREIGN_LLM_ID), eq(AccessLevel.VIEW), anyString())).thenThrow(new ForbiddenException("no"));

        assertThrows(ForbiddenException.class, () -> exportService.exportAgent(AGENT_ID, 1, null, null, null));

        verify(llmStore, never()).read(anyString(), anyInt());
        // The step's own reference was vetted when the workflow was written.
        verify(guard, never()).requireAccess(eq(HTTPCALLS_ID), eq(AccessLevel.VIEW), anyString());
    }

    @Test
    @DisplayName("the export preview applies the same check")
    void previewChecksUnvettedReference() throws Exception {
        var config = new LinkedHashMap<String, Object>();
        config.put("uri", "eddi://ai.labs.apicalls/apicallstore/apicalls/" + HTTPCALLS_ID + "?version=1");
        config.put("note", "eddi://ai.labs.llm/llmstore/llms/" + FOREIGN_LLM_ID + "?version=1");
        workflow = workflowNaming(config);
        when(guard.requireAccess(eq(FOREIGN_LLM_ID), eq(AccessLevel.VIEW), anyString())).thenThrow(new ForbiddenException("no"));

        assertThrows(ForbiddenException.class, () -> exportService.previewExport(AGENT_ID, 1));

        verify(documentDescriptorStore, never()).readDescriptor(eq(FOREIGN_LLM_ID), any());
    }

    @Test
    @DisplayName("a snippet the caller may only USE is not read into the archive")
    void useOnlySnippetStaysBehind() throws Exception {
        workflow.getWorkflowSteps().getFirst().getConfig().put("prompt", "{snippets.house_rules}");
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.snippet/snippetstore/snippets/1234567890abcdef12345678?version=1"));
        when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(), anyBoolean(), any()))
                .thenReturn(new ArrayList<>(List.of(descriptor)));
        when(guard.canAccess(descriptor, AccessLevel.VIEW)).thenReturn(false);

        exportService.exportAgent(AGENT_ID, 1, null, null, null);

        verify(snippetStore, never()).read(anyString(), anyInt());
    }

    @Test
    @DisplayName("a connection's legacy literal password is redacted as the editor read redacts it")
    void connectionLegacyLiteralIsRedacted() throws Exception {
        workflow.getWorkflowSteps().getFirst().getConfig().put("auth", "${connection:legacy}");
        var staticAuth = new StaticAuth();
        staticAuth.setUsername("svc");
        staticAuth.setPasswordRef("hunter2-literal");
        var connection = new ConnectionConfiguration();
        connection.setName("legacy");
        connection.setStaticAuth(staticAuth);
        when(connectionStore.idOfName("default", "legacy")).thenReturn(CONNECTION_ID);
        when(connectionStore.readByName("default", "legacy")).thenReturn(connection);
        var written = new AtomicReference<String>();
        doAnswer(invocation -> {
            Path source = Paths.get(invocation.getArgument(0, String.class));
            written.set(Files.readString(source.resolve("connections").resolve(CONNECTION_ID + ".connection.json")));
            return null;
        }).when(zipArchive).createZip(anyString(), anyString(), any());

        exportService.exportAgent(AGENT_ID, 1, null, null, null);

        assertNotNull(written.get());
        assertFalse(written.get().contains("hunter2-literal"), written.get());
        assertTrue(written.get().contains("svc"), "the rest of the document travels: " + written.get());
    }

    @Test
    @DisplayName("an agent-shaped reference carrying a configuration's id does not exempt that configuration from VIEW")
    void agentShapedReferenceDoesNotVetAConfiguration() throws Exception {
        var agentStep = new WorkflowConfiguration.WorkflowStep();
        agentStep.setType(URI.create("eddi://ai.labs.agent"));
        // The authoring check gives an agent reference USE only.
        agentStep.setConfig(new LinkedHashMap<>(Map.of("uri", "eddi://ai.labs.agent/agentstore/agents/" + FOREIGN_LLM_ID + "?version=1")));
        var config = new LinkedHashMap<String, Object>();
        config.put("uri", "eddi://ai.labs.apicalls/apicallstore/apicalls/" + HTTPCALLS_ID + "?version=1");
        config.put("note", "eddi://ai.labs.llm/llmstore/llms/" + FOREIGN_LLM_ID + "?version=1");
        workflow = workflowNaming(config);
        workflow.getWorkflowSteps().add(agentStep);
        when(guard.requireAccess(eq(FOREIGN_LLM_ID), eq(AccessLevel.VIEW), anyString())).thenThrow(new ForbiddenException("no"));

        assertThrows(ForbiddenException.class, () -> exportService.exportAgent(AGENT_ID, 1, null, null, null));

        verify(llmStore, never()).read(anyString(), anyInt());
    }

    @Test
    @DisplayName("a workflow the agent names repeatedly is read and archived once")
    void repeatedWorkflowIsReadOnce() throws Exception {
        URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1");
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(workflowUri, workflowUri, workflowUri));
        when(agentStore.read(AGENT_ID, 1)).thenReturn(agentConfig);

        exportService.exportAgent(AGENT_ID, 1, null, null, null);

        verify(workflowStore, times(1)).read(WORKFLOW_ID, 1);
    }

    @Test
    @DisplayName("connection references are found in linear time, unterminated openers or not")
    void connectionScanIsLinear() {
        // Nothing after the openers closes them: the regex rescanned the rest of the
        // text from each one.
        String openers = "${connection:".repeat(40_000);
        Set<ConnectionReference> found = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> RestExportService.extractConnectionReferences(List.of("${connection:jira} " + openers)));

        assertTrue(found.contains(new ConnectionReference("default", "jira")), "found: " + found);
    }

    private static WorkflowConfiguration workflowNaming(Map<String, Object> config) {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.apicalls"));
        step.setConfig(new LinkedHashMap<>(config));
        var workflow = new WorkflowConfiguration();
        workflow.setWorkflowSteps(new ArrayList<>(List.of(step)));
        return workflow;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
