/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.configs.parser.IParserStore;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A parser document and the dictionaries it names travel with the agent.
 * <p>
 * They used to stay behind: the archive carried the workflow's parser step,
 * whose {@code config.uri} named a parser document on the source instance, and
 * nothing else. Every agent the setup wizard creates has such a step, so every
 * one of them arrived on the target referencing a parser that did not exist
 * there. The other half is what this must <em>not</em> change: every archive
 * written before parser documents travelled carries the step without the
 * document, and those have to import exactly as they always did.
 */
@DisplayName("RestImportService — parser documents travel with the agent")
class RestImportServiceParserTravelTest {

    private static final String AGENT_ORIGIN_ID = "aabb11112222333344445555";
    private static final String NEW_AGENT_ID = "ccdd11112222333344445555";
    private static final String WORKFLOW_ID = "bbbb11112222333344445555";
    private static final String NEW_WORKFLOW_ID = "7777111122223333444455bb";
    private static final String PARSER_ID = "eeee11112222333344445555";
    private static final String NEW_PARSER_ID = "ffff11112222333344445555";
    private static final String DICT_ID = "1111222233334444aaaabbbb";
    private static final String NEW_DICT_ID = "2222333344445555aaaabbbb";

    private static final String PARSER_URI = "eddi://ai.labs.parser/parserstore/parsers/" + PARSER_ID + "?version=1";
    private static final String DICT_URI = "eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + DICT_ID + "?version=1";
    private static final String NEW_DICT_URI = "eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + NEW_DICT_ID
            + "?version=1";

    private IZipArchive zipArchive;
    private IDocumentDescriptorStore documentDescriptorStore;
    private RestImportService importService;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @BeforeEach
    void setUp() throws Exception {
        zipArchive = mock(IZipArchive.class);
        IJsonSerialization jsonSerialization = mock(IJsonSerialization.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        var templateSyntaxMigrator = mock(TemplateSyntaxMigrator.class);
        when(templateSyntaxMigrator.migrate(anyString())).thenAnswer(inv -> inv.getArgument(0));

        importService = new RestImportService(
                zipArchive, jsonSerialization,
                mock(IMigrationManager.class), documentDescriptorStore,
                templateSyntaxMigrator, mock(StructuralMatcher.class),
                mock(UpgradeExecutor.class), mock(IScheduleStore.class), mock(BackupMetrics.class),
                mock(ResourceAccessGuard.class), mock(SpaceContext.class), mock(RagSourceIngestionService.class),
                true, false, Optional.empty());

        for (Class<?> type : List.of(AgentConfiguration.class, WorkflowConfiguration.class, DocumentDescriptor.class,
                ParserConfiguration.class, DictionaryConfiguration.class)) {
            when(jsonSerialization.deserialize(anyString(), eq(type)))
                    .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), type));
        }
        when(jsonSerialization.serialize(any())).thenAnswer(inv -> mapper.writeValueAsString(inv.getArgument(0)));
    }

    @Test
    @DisplayName("the parser document is created, and names this instance's copy of its dictionary")
    void parserDocumentAndItsDictionaryAreCreated() throws Exception {
        // The dictionary is named ONLY by the parser document, not by the workflow:
        // the export has to find it there, and the import has to both create it and
        // repoint the parser at it.
        archive(true);

        var workflowStore = mock(IWorkflowStore.class);
        when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));
        var parserStore = mock(IParserStore.class);
        when(parserStore.create(any())).thenReturn(resourceId(NEW_PARSER_ID, 1));
        var dictionaryStore = mock(IDictionaryStore.class);
        when(dictionaryStore.create(any())).thenReturn(resourceId(NEW_DICT_ID, 1));

        try (var cdi = stubCdi(IAgentStore.class, stubAgentCreation(),
                IWorkflowStore.class, workflowStore,
                IParserStore.class, parserStore,
                IDictionaryStore.class, dictionaryStore)) {
            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "create", null, null, null);
            assertEquals(201, response.getStatus());
        }

        verify(dictionaryStore).create(any());

        var parser = ArgumentCaptor.forClass(ParserConfiguration.class);
        verify(parserStore).create(parser.capture());
        String parserJson = mapper.writeValueAsString(parser.getValue());
        assertTrue(parserJson.contains(NEW_DICT_URI),
                "the parser must name the dictionary created here, was: " + parserJson);
        assertFalse(parserJson.contains(DICT_ID),
                "the source's dictionary id names nothing on this instance, was: " + parserJson);

        var workflow = ArgumentCaptor.forClass(WorkflowConfiguration.class);
        verify(workflowStore).create(workflow.capture());
        Object parserRef = workflow.getValue().getWorkflowSteps().getFirst().getConfig().get("uri");
        assertEquals("eddi://ai.labs.parser/parserstore/parsers/" + NEW_PARSER_ID + "?version=1", parserRef,
                "the workflow's parser step must name the parser created here");
    }

    @Test
    @DisplayName("an archive whose parser names a dictionary it does not carry still imports")
    void parserDictionaryLeftOutOfTheArchive() throws Exception {
        // A selective export that deselected the dictionary. The parser keeps naming
        // it as the archive wrote it — the pipeline never loads the document — rather
        // than the whole import failing over a file the product chose to leave out.
        stubArchive(false, true);

        var workflowStore = mock(IWorkflowStore.class);
        when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));
        var parserStore = mock(IParserStore.class);
        when(parserStore.create(any())).thenReturn(resourceId(NEW_PARSER_ID, 1));
        var dictionaryStore = mock(IDictionaryStore.class);

        try (var cdi = stubCdi(IAgentStore.class, stubAgentCreation(),
                IWorkflowStore.class, workflowStore,
                IParserStore.class, parserStore,
                IDictionaryStore.class, dictionaryStore)) {
            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "create", null, null, null);
            assertEquals(201, response.getStatus());
        }

        verify(dictionaryStore, never()).create(any());
        var parser = ArgumentCaptor.forClass(ParserConfiguration.class);
        verify(parserStore).create(parser.capture());
        assertTrue(mapper.writeValueAsString(parser.getValue()).contains(DICT_URI));
    }

    @Test
    @DisplayName("a dictionary both the parser step and its document name is created once")
    void sharedDictionaryIsCreatedOnce() throws Exception {
        stubArchive(true, false);

        var workflowStore = mock(IWorkflowStore.class);
        when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));
        var parserStore = mock(IParserStore.class);
        when(parserStore.create(any())).thenReturn(resourceId(NEW_PARSER_ID, 1));
        var dictionaryStore = mock(IDictionaryStore.class);
        when(dictionaryStore.create(any())).thenReturn(resourceId(NEW_DICT_ID, 1));

        try (var cdi = stubCdi(IAgentStore.class, stubAgentCreation(),
                IWorkflowStore.class, workflowStore,
                IParserStore.class, parserStore,
                IDictionaryStore.class, dictionaryStore)) {
            importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
        }

        // Twice would leave the first copy an orphan: every reference is repointed
        // at the second.
        verify(dictionaryStore, times(1)).create(any());
    }

    @Test
    @DisplayName("an archive written before parsers travelled imports as it always did")
    void legacyArchiveKeepsItsParserStep() throws Exception {
        archive(false);

        var workflowStore = mock(IWorkflowStore.class);
        when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));
        var parserStore = mock(IParserStore.class);

        try (var cdi = stubCdi(IAgentStore.class, stubAgentCreation(),
                IWorkflowStore.class, workflowStore,
                IParserStore.class, parserStore)) {
            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "create", null, null, null);
            assertEquals(201, response.getStatus(),
                    "an archive this product wrote must still import");
        }

        var workflow = ArgumentCaptor.forClass(WorkflowConfiguration.class);
        verify(workflowStore).create(workflow.capture());
        List<WorkflowConfiguration.WorkflowStep> steps = workflow.getValue().getWorkflowSteps();
        // Dropping the step for lacking its document is what the create path does
        // for every other type — for a parser it would leave an agent that cannot
        // parse any input.
        assertEquals(1, steps.size(), "the parser step must survive");
        assertEquals(PARSER_URI, steps.getFirst().getConfig().get("uri"),
                "the reference is left as the archive wrote it");
        verify(parserStore, never()).create(any());
    }

    @Test
    @DisplayName("the preview lists a parser the archive carries, and none it does not")
    void previewListsOnlyCarriedParsers() throws Exception {
        archive(true);
        ImportPreview carried = importService.previewImport(new ByteArrayInputStream(new byte[0]), null);
        assertEquals(1, rowsOfType(carried, AbstractBackupService.PARSER_EXT));
        assertEquals(1, rowsOfType(carried, AbstractBackupService.DICTIONARY_EXT),
                "the dictionary only the parser names is part of what lands too");

        archive(false);
        ImportPreview legacy = importService.previewImport(new ByteArrayInputStream(new byte[0]), null);
        assertEquals(0, rowsOfType(legacy, AbstractBackupService.PARSER_EXT),
                "a row for a parser the archive lacks would promise a write the import never makes");
    }

    // ==================== Helpers ====================

    private static long rowsOfType(ImportPreview preview, String type) {
        return preview.resources().stream().filter(diff -> type.equals(diff.resourceType())).count();
    }

    /**
     * An agent whose one workflow has a parser step pointing at a parser document,
     * which in turn names a dictionary — the shape the setup wizard creates, plus a
     * dictionary.
     */
    private void archive(boolean withParserDocument) throws Exception {
        stubUnzip(dir -> {
            Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(),
                    "{\"workflows\":[\"eddi://ai.labs.workflow/workflowstore/workflows/"
                            + WORKFLOW_ID + "?version=1\"]}");
            File versionDir = new File(dir, WORKFLOW_ID + "/1");
            assertTrue(versionDir.mkdirs() || versionDir.isDirectory());
            Files.writeString(new File(versionDir, WORKFLOW_ID + ".workflow.json").toPath(),
                    "{\"workflowSteps\":[{\"type\":\"eddi://ai.labs.parser\",\"config\":{\"uri\":\""
                            + PARSER_URI + "\"}}]}");
            if (withParserDocument) {
                Files.writeString(new File(versionDir, PARSER_ID + ".parser.json").toPath(),
                        "{\"extensions\":{\"dictionaries\":[{\"type\":\"eddi://ai.labs.parser.dictionaries.regular\","
                                + "\"config\":{\"uri\":\"" + DICT_URI + "\"}}]},\"config\":{}}");
                Files.writeString(new File(versionDir, DICT_ID + ".regulardictionary.json").toPath(),
                        "{\"words\":[]}");
            }
        });
    }

    /**
     * A parser step naming a parser document that names a dictionary.
     *
     * @param stepNamesDictionaryToo
     *            whether the step's own extensions name the same dictionary — twice
     * @param dictionaryLeftOut
     *            whether the archive leaves the dictionary's file out
     */
    private void stubArchive(boolean stepNamesDictionaryToo, boolean dictionaryLeftOut) throws Exception {
        String dictionaryRef = "{\"type\":\"eddi://ai.labs.parser.dictionaries.regular\",\"config\":{\"uri\":\""
                + DICT_URI + "\"}}";
        stubUnzip(dir -> {
            Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(),
                    "{\"workflows\":[\"eddi://ai.labs.workflow/workflowstore/workflows/"
                            + WORKFLOW_ID + "?version=1\"]}");
            File versionDir = new File(dir, WORKFLOW_ID + "/1");
            assertTrue(versionDir.mkdirs() || versionDir.isDirectory());
            Files.writeString(new File(versionDir, WORKFLOW_ID + ".workflow.json").toPath(),
                    "{\"workflowSteps\":[{\"type\":\"eddi://ai.labs.parser\",\"config\":{\"uri\":\""
                            + PARSER_URI + "\"}"
            // Twice: once per mention used to mean once per create.
                            + (stepNamesDictionaryToo
                                    ? ",\"extensions\":{\"dictionaries\":[" + dictionaryRef + "," + dictionaryRef + "]}"
                                    : "")
                            + "}]}");
            Files.writeString(new File(versionDir, PARSER_ID + ".parser.json").toPath(),
                    "{\"extensions\":{\"dictionaries\":[" + dictionaryRef + "]},\"config\":{}}");
            if (!dictionaryLeftOut) {
                Files.writeString(new File(versionDir, DICT_ID + ".regulardictionary.json").toPath(),
                        "{\"words\":[]}");
            }
        });
    }

    private interface ArchiveContent {
        void write(File dir) throws IOException;
    }

    private void stubUnzip(ArchiveContent content) throws Exception {
        doAnswer(inv -> {
            File dir = inv.getArgument(1);
            assertTrue(dir.mkdirs() || dir.isDirectory());
            content.write(dir);
            return null;
        }).when(zipArchive).unzip(any(InputStream.class), any(File.class));
    }

    private IAgentStore stubAgentCreation() throws Exception {
        var agentStore = mock(IAgentStore.class);
        when(agentStore.create(any())).thenReturn(resourceId(NEW_AGENT_ID, 1));
        when(documentDescriptorStore.getCurrentResourceId(NEW_AGENT_ID)).thenReturn(resourceId(NEW_AGENT_ID, 1));

        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + NEW_AGENT_ID + "?version=1"));
        when(documentDescriptorStore.readDescriptor(NEW_AGENT_ID, 1)).thenReturn(descriptor);
        return agentStore;
    }

    @SuppressWarnings("unchecked")
    private AutoCloseable stubCdi(Object... classThenStore) {
        var cdiMock = mockStatic(CDI.class);
        var cdi = mock(CDI.class);
        cdiMock.when(CDI::current).thenReturn(cdi);
        for (int i = 0; i < classThenStore.length; i += 2) {
            select(cdi, (Class<Object>) classThenStore[i], classThenStore[i + 1]);
        }
        return cdiMock;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> void select(CDI cdi, Class<T> storeClass, T store) {
        var instance = (Instance<T>) mock(Instance.class);
        when(cdi.select(storeClass)).thenReturn(instance);
        when(instance.get()).thenReturn(store);
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
