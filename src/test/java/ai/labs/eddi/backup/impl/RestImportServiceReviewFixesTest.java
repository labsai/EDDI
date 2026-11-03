/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.configs.parser.IParserStore;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.ForbiddenException;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review fixes on the ZIP import path: what a workflow directory must contain,
 * which references a directly created workflow may keep, and that a merged
 * parser is rolled back with everything else.
 */
@DisplayName("RestImportService — review fixes")
class RestImportServiceReviewFixesTest {

    private static final String AGENT_ORIGIN_ID = "aaaa11112222333344445555";
    private static final String WORKFLOW_ID = "bbbb11112222333344445551";
    private static final String PARSER_ORIGIN_ID = "dddd11112222333344445555";
    private static final String LOCAL_PARSER_ID = "eeee11112222333344445555";
    private static final String FOREIGN_PARSER_ID = "ffff11112222333344445555";
    private static final String FOREIGN_DICTIONARY_ID = "ffff11112222333344445556";
    private static final String RULES_ID = "abab11112222333344445555";

    private IZipArchive zipArchive;
    private IJsonSerialization jsonSerialization;
    private IDocumentDescriptorStore documentDescriptorStore;
    private ResourceAccessGuard guard;
    private RestImportService importService;
    private IWorkflowStore workflowStore;
    private IAgentStore agentStore;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        zipArchive = mock(IZipArchive.class);
        jsonSerialization = mock(IJsonSerialization.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        guard = mock(ResourceAccessGuard.class);
        var templateSyntaxMigrator = mock(TemplateSyntaxMigrator.class);
        when(templateSyntaxMigrator.migrate(anyString())).thenAnswer(i -> i.getArgument(0));

        importService = new RestImportService(zipArchive, jsonSerialization, mock(IMigrationManager.class), documentDescriptorStore,
                templateSyntaxMigrator, mock(StructuralMatcher.class), mock(UpgradeExecutor.class), mock(IScheduleStore.class),
                mock(BackupMetrics.class), guard, mock(SpaceContext.class), mock(RagSourceIngestionService.class), true, false,
                Optional.empty());

        workflowStore = mock(IWorkflowStore.class);
        when(workflowStore.create(any())).thenReturn(resourceId("cccc11112222333344445551", 1));
        agentStore = mock(IAgentStore.class);
        when(agentStore.create(any())).thenReturn(resourceId("cccc11112222333344445559", 1));
        when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class)))
                .thenAnswer(i -> mapper.readValue((String) i.getArgument(0), WorkflowConfiguration.class));
    }

    @Test
    @DisplayName("a workflow directory without a workflow document is a bad archive, not a kept foreign reference")
    void workflowDirectoryWithoutDocumentIsRejected() throws Exception {
        givenArchive(List.of(WORKFLOW_ID), workflowDir -> {
            // the directory the agent's URI points at, and nothing in it
        });

        var thrown = assertThrows(WebApplicationException.class, () -> runImport("create"));

        assertEquals(400, thrown.getResponse().getStatus());
        verify(agentStore, never()).create(any());
    }

    @Test
    @DisplayName("a workflow the agent names twice is imported once")
    void repeatedWorkflowIsImportedOnce() throws Exception {
        givenArchive(List.of(WORKFLOW_ID, WORKFLOW_ID), workflowDir -> Files.writeString(
                new File(workflowDir, WORKFLOW_ID + ".workflow.json").toPath(), "{\"workflowSteps\":[]}"));

        Response response = runImport("create");

        assertEquals(201, response.getStatus());
        verify(workflowStore, times(1)).create(any());
    }

    @Test
    @DisplayName("a reference the import keeps as written needs VIEW, as on the REST create")
    void keptReferenceIsChecked() throws Exception {
        // A parser document the archive does not carry: the step is kept, naming an id
        // on this deployment.
        String parserUri = "eddi://ai.labs.parser/parserstore/parsers/" + FOREIGN_PARSER_ID + "?version=1";
        givenArchive(List.of(WORKFLOW_ID), workflowDir -> Files.writeString(
                new File(workflowDir, WORKFLOW_ID + ".workflow.json").toPath(),
                "{\"workflowSteps\":[{\"type\":\"eddi://ai.labs.parser\",\"config\":{\"uri\":\"" + parserUri + "\"}}]}"));
        doThrow(new ForbiddenException("no")).when(guard).requireAccess(eq(FOREIGN_PARSER_ID), eq(AccessLevel.VIEW), anyString());

        assertThrows(ForbiddenException.class, () -> runImport("create"));

        verify(workflowStore, never()).create(any());
    }

    @Test
    @DisplayName("a merged parser is restored when the import fails later, like every other update")
    void mergedParserIsRolledBack() throws Exception {
        String parserUri = "eddi://ai.labs.parser/parserstore/parsers/" + PARSER_ORIGIN_ID + "?version=1";
        givenArchive(List.of(WORKFLOW_ID), workflowDir -> {
            Files.writeString(new File(workflowDir, WORKFLOW_ID + ".workflow.json").toPath(),
                    "{\"workflowSteps\":[{\"type\":\"eddi://ai.labs.parser\",\"config\":{\"uri\":\"" + parserUri + "\"}}]}");
            Files.writeString(new File(workflowDir, PARSER_ORIGIN_ID + ".parser.json").toPath(), "PARSERJSON");
        });
        when(jsonSerialization.deserialize(eq("PARSERJSON"), eq(ParserConfiguration.class))).thenAnswer(i -> new ParserConfiguration());

        var original = new ParserConfiguration();
        var parserStore = mock(IParserStore.class);
        when(parserStore.read(LOCAL_PARSER_ID, 1)).thenReturn(original);
        var restParserStore = mock(IRestParserStore.class);
        List<Object> written = new ArrayList<>();
        when(restParserStore.updateParser(eq(LOCAL_PARSER_ID), anyInt(), any())).thenAnswer(i -> {
            written.add(i.getArgument(2));
            return Response.ok().build();
        });
        var local = new DocumentDescriptor();
        local.setResource(URI.create("eddi://ai.labs.parser/parserstore/parsers/" + LOCAL_PARSER_ID + "?version=1"));
        when(documentDescriptorStore.findByOriginId(PARSER_ORIGIN_ID)).thenReturn(List.of(local));
        when(agentStore.create(any())).thenThrow(new IResourceStore.ResourceStoreException("agent store unavailable"));

        assertThrows(RuntimeException.class, () -> runImport("merge", parserStore, restParserStore));

        assertEquals(2, written.size(), "the merge update and its compensation");
        assertSame(original, written.get(1), "restored to the content from before the import");
    }

    @Test
    @DisplayName("a parser document naming a dictionary the archive left out needs VIEW on it, as on the REST create")
    void parserDocumentReferenceIsChecked() throws Exception {
        String parserUri = "eddi://ai.labs.parser/parserstore/parsers/" + PARSER_ORIGIN_ID + "?version=1";
        givenArchive(List.of(WORKFLOW_ID), workflowDir -> {
            Files.writeString(new File(workflowDir, WORKFLOW_ID + ".workflow.json").toPath(),
                    "{\"workflowSteps\":[{\"type\":\"eddi://ai.labs.parser\",\"config\":{\"uri\":\"" + parserUri + "\"}}]}");
            Files.writeString(new File(workflowDir, PARSER_ORIGIN_ID + ".parser.json").toPath(), "PARSERJSON");
        });
        var parser = new ParserConfiguration();
        parser.setExtensions(Map.of("dictionaries", List.of(Map.of("type", "eddi://ai.labs.parser.dictionaries.regular",
                "config", Map.of("uri", "eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + FOREIGN_DICTIONARY_ID + "?version=1")))));
        when(jsonSerialization.deserialize(eq("PARSERJSON"), eq(ParserConfiguration.class))).thenReturn(parser);
        doThrow(new ForbiddenException("no")).when(guard).requireAccess(eq(FOREIGN_DICTIONARY_ID), eq(AccessLevel.VIEW), anyString());
        var parserStore = mock(IParserStore.class);

        assertThrows(ForbiddenException.class, () -> runImport("create", parserStore, mock(IRestParserStore.class)));

        verify(parserStore, never()).create(any());
    }

    @Test
    @DisplayName("a workflow directory holding two workflow documents is a bad archive")
    void twoWorkflowDocumentsAreRejected() throws Exception {
        givenArchive(List.of(WORKFLOW_ID), workflowDir -> {
            Files.writeString(new File(workflowDir, WORKFLOW_ID + ".workflow.json").toPath(), "{\"workflowSteps\":[]}");
            Files.writeString(new File(workflowDir, "other.workflow.json").toPath(), "{\"workflowSteps\":[]}");
        });

        var thrown = assertThrows(WebApplicationException.class, () -> runImport("create"));

        assertEquals(400, thrown.getResponse().getStatus());
        verify(workflowStore, never()).create(any());
    }

    @Test
    @DisplayName("two spellings of one workflow URI are one workflow, imported once")
    void workflowSpellingsAreImportedOnce() throws Exception {
        givenArchive(List.of(WORKFLOW_ID), workflowDir -> Files.writeString(
                new File(workflowDir, WORKFLOW_ID + ".workflow.json").toPath(), "{\"workflowSteps\":[]}"));
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(
                URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1"),
                URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1&alias=2")));
        when(jsonSerialization.deserialize(eq("AGENTJSON"), eq(AgentConfiguration.class))).thenReturn(agentConfig);
        List<AgentConfiguration> created = new ArrayList<>();
        when(agentStore.create(any())).thenAnswer(i -> {
            created.add(i.getArgument(0));
            return resourceId("cccc11112222333344445559", 1);
        });

        assertEquals(201, runImport("create").getStatus());

        verify(workflowStore, times(1)).create(any());
        URI imported = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/cccc11112222333344445551?version=1");
        assertEquals(List.of(imported, imported), created.getFirst().getWorkflows());
    }

    @Test
    @DisplayName("versions of one id share the one archived file, so it is written once for all of them")
    void versionAliasesAreWrittenOnce() throws Exception {
        String rules = "eddi://ai.labs.rules/rulestore/rulesets/" + RULES_ID;
        givenArchive(List.of(WORKFLOW_ID), workflowDir -> {
            Files.writeString(new File(workflowDir, WORKFLOW_ID + ".workflow.json").toPath(), "{\"workflowSteps\":["
                    + "{\"type\":\"eddi://ai.labs.rules\",\"config\":{\"uri\":\"" + rules + "?version=1\"}},"
                    + "{\"type\":\"eddi://ai.labs.rules\",\"config\":{\"uri\":\"" + rules + "?version=2\"}},"
                    + "{\"type\":\"eddi://ai.labs.rules\",\"config\":{\"uri\":\"" + rules + "?version=3\"}}]}");
            Files.writeString(new File(workflowDir, RULES_ID + ".behavior.json").toPath(), "RULESJSON");
        });
        when(jsonSerialization.deserialize(eq("RULESJSON"), eq(RuleSetConfiguration.class))).thenAnswer(i -> new RuleSetConfiguration());
        var ruleSetStore = mock(IRuleSetStore.class);
        when(ruleSetStore.create(any())).thenReturn(resourceId("cccc11112222333344445552", 1));

        assertEquals(201, runImport("create", Map.of(IRuleSetStore.class, ruleSetStore)).getStatus());

        verify(ruleSetStore, times(1)).create(any());
    }

    // ==================== fixtures ====================

    @FunctionalInterface
    private interface WorkflowDirWriter {
        void write(File workflowDir) throws Exception;
    }

    private void givenArchive(List<String> workflowIds, WorkflowDirWriter writer) throws Exception {
        doAnswer(inv -> {
            File dir = inv.getArgument(1);
            dir.mkdirs();
            Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "AGENTJSON");
            for (String workflowId : workflowIds.stream().distinct().toList()) {
                File workflowDir = new File(new File(dir, workflowId), "1");
                workflowDir.mkdirs();
                writer.write(workflowDir);
            }
            return null;
        }).when(zipArchive).unzip(any(InputStream.class), any(File.class));
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(workflowIds.stream()
                .map(id -> URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + id + "?version=1")).toList());
        when(jsonSerialization.deserialize(eq("AGENTJSON"), eq(AgentConfiguration.class))).thenReturn(agentConfig);
    }

    private Response runImport(String strategy) {
        return runImport(strategy, mock(IParserStore.class), mock(IRestParserStore.class));
    }

    private Response runImport(String strategy, IParserStore parserStore, IRestParserStore restParserStore) {
        return runImport(strategy, Map.of(IParserStore.class, parserStore, IRestParserStore.class, restParserStore));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Response runImport(String strategy, Map<Class<?>, Object> beans) {
        try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
            CDI cdi = mock(CDI.class);
            cdiMock.when(CDI::current).thenReturn(cdi);
            stubBean(cdi, IWorkflowStore.class, workflowStore);
            stubBean(cdi, IAgentStore.class, agentStore);
            stubBean(cdi, CapabilityRegistryService.class, mock(CapabilityRegistryService.class));
            if (!beans.containsKey(IParserStore.class)) {
                stubBean(cdi, IParserStore.class, mock(IParserStore.class));
                stubBean(cdi, IRestParserStore.class, mock(IRestParserStore.class));
            }
            beans.forEach((beanClass, bean) -> stubBean(cdi, (Class) beanClass, bean));
            return importService.importAgent(new ByteArrayInputStream(new byte[0]), strategy, null, null, null);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> void stubBean(CDI cdi, Class<T> beanClass, T bean) {
        Instance<T> instance = mock(Instance.class);
        when(cdi.select(beanClass)).thenReturn(instance);
        when(instance.get()).thenReturn(bean);
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
