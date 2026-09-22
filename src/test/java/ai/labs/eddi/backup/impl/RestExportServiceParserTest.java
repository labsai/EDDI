/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.parser.IParserStore;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.snippets.IPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import ai.labs.eddi.utils.FileUtilities;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An export carries the parser document a workflow's parser step names, and the
 * dictionaries that document names in turn.
 * <p>
 * The archive contents are sampled while the ZIP is being built, because the
 * export deletes its scratch tree as soon as it returns.
 */
@DisplayName("RestExportService — parser documents")
class RestExportServiceParserTest {

    private static final String AGENT_ID = "aaaa00001111222233334444";
    private static final String WORKFLOW_ID = "bbbb00001111222233334444";
    private static final String PARSER_ID = "cccc00001111222233334444";
    private static final String DICT_ID = "dddd00001111222233334444";

    private static final String PARSER_FILE = WORKFLOW_ID + "/1/" + PARSER_ID + ".parser.json";
    private static final String DICT_FILE = WORKFLOW_ID + "/1/" + DICT_ID + ".regulardictionary.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> archivedFiles = new ArrayList<>();

    private IParserStore parserStore;
    private IDictionaryStore dictionaryStore;
    private RestExportService exportService;
    private Path tmpDir;

    @BeforeEach
    void setUp() throws Exception {
        var agentStore = mock(IAgentStore.class);
        var workflowStore = mock(IWorkflowStore.class);
        var documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        var jsonSerialization = mock(IJsonSerialization.class);
        var secretScrubber = mock(SecretScrubber.class);
        var zipArchive = mock(IZipArchive.class);
        parserStore = mock(IParserStore.class);
        dictionaryStore = mock(IDictionaryStore.class);

        exportService = new RestExportService(
                documentDescriptorStore, agentStore, workflowStore,
                parserStore, dictionaryStore, mock(IRuleSetStore.class), mock(IApiCallsStore.class),
                mock(ILlmStore.class), mock(IPropertySetterStore.class), mock(IOutputStore.class),
                mock(IMcpCallsStore.class), mock(IRagStore.class), mock(IPromptSnippetStore.class),
                jsonSerialization, zipArchive, secretScrubber, mock(IScheduleStore.class),
                mock(ResourceAccessGuard.class), mock(BackupMetrics.class), mock(IConnectionStore.class));

        tmpDir = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp"));

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create(
                "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1")));
        when(agentStore.read(AGENT_ID, 1)).thenReturn(agentConfig);

        // The shape the setup wizard creates: a parser step naming a parser document
        // by config.uri, and nothing else. The dictionary is named by the DOCUMENT.
        var workflow = mapper.readValue("{\"workflowSteps\":[{\"type\":\"eddi://ai.labs.parser\",\"config\":"
                + "{\"uri\":\"eddi://ai.labs.parser/parserstore/parsers/" + PARSER_ID + "?version=1\"}}]}",
                WorkflowConfiguration.class);
        when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(workflow);
        var parser = mapper.readValue("{\"extensions\":{\"dictionaries\":[{\"type\":"
                + "\"eddi://ai.labs.parser.dictionaries.regular\",\"config\":{\"uri\":"
                + "\"eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + DICT_ID + "?version=1\"}}]}}",
                ParserConfiguration.class);
        when(parserStore.read(PARSER_ID, 1)).thenReturn(parser);
        when(dictionaryStore.read(DICT_ID, 1)).thenReturn(new DictionaryConfiguration());

        when(jsonSerialization.serialize(any())).thenAnswer(inv -> mapper.writeValueAsString(inv.getArgument(0)));
        when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), WorkflowConfiguration.class));
        when(secretScrubber.scrubJson(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(documentDescriptorStore.readDescriptorWithHistory(anyString(), any())).thenReturn(new DocumentDescriptor());

        doAnswer(inv -> {
            Path zipRoot = Paths.get(inv.getArgument(0, String.class));
            try (var paths = Files.walk(zipRoot)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    archivedFiles.add(zipRoot.relativize(path).toString().replace('\\', '/'));
                }
            }
            return null;
        }).when(zipArchive).createZip(anyString(), anyString(), any());
    }

    @AfterEach
    void tearDown() throws IOException {
        for (String dir : List.of("export", "archives")) {
            Path root = tmpDir.resolve(dir);
            if (!Files.exists(root)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    @DisplayName("the parser document and the dictionary only it names are both in the archive")
    void parserAndItsDictionaryAreExported() {
        Response response = exportService.exportAgent(AGENT_ID, 1, null, null, null);

        assertEquals(200, response.getStatus());
        assertTrue(archivedFiles.stream().anyMatch(f -> f.endsWith(PARSER_FILE)),
                "the parser document must travel, got " + archivedFiles);
        assertTrue(archivedFiles.stream().anyMatch(f -> f.endsWith(DICT_FILE)),
                "a dictionary named only by the parser document must travel too, got " + archivedFiles);
    }

    @Test
    @DisplayName("a parser document that no longer exists is left out, not fatal to the export")
    void missingParserDoesNotFailTheExport() throws Exception {
        // Every agent imported from an archive written before parser documents
        // travelled names one its own instance never had. Refusing to export it would
        // block every backup and promotion of that agent over a reference the
        // pipeline never loads.
        when(parserStore.read(PARSER_ID, 1)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));

        Response response = exportService.exportAgent(AGENT_ID, 1, null, null, null);

        assertEquals(200, response.getStatus());
        assertFalse(archivedFiles.stream().anyMatch(f -> f.endsWith(PARSER_FILE)), "got " + archivedFiles);
        assertTrue(archivedFiles.stream().anyMatch(f -> f.endsWith(WORKFLOW_ID + ".workflow.json")),
                "the rest of the agent must still be exported, got " + archivedFiles);
    }
}
