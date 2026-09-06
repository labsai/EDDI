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
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.BadRequestException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What {@code strategy=create} does with an archive that references a
 * configuration it does not carry.
 * <p>
 * EDDI's own selective export writes the workflow whole — references to the
 * omitted configs included — because that is what {@code strategy=merge} needs:
 * it answers such a reference from the target's own copy, and a workflow
 * arriving without the step would <em>delete</em> that step from the live
 * agent. A create has no copy to fall back on, so it used to fail the whole
 * import with "The archive references … but does not contain …" — an
 * instruction the operator could not act on, because the product had written
 * the archive. It now drops what it cannot honour, and says so.
 * <p>
 * The pruning is deliberately narrow, and each limit is asserted here: a step
 * whose own {@code config.uri} is missing goes entirely, a nested list entry
 * loses only that entry, and anything the walk cannot classify — an unparseable
 * URI, a resource type the backup package does not move, a reference nested
 * deeper than the recursion guard — is left exactly where it is.
 */
@DisplayName("RestImportService — unresolvable references on a create")
class RestImportServiceUnresolvableReferenceTest {

    private static final String AGENT_ORIGIN_ID = "aabb11112222333344445555";
    private static final String NEW_AGENT_ID = "ccdd11112222333344445555";
    private static final String WORKFLOW_ID = "bbbb11112222333344445555";
    private static final String BEHAVIOR_ID = "eeee11112222333344445555";
    private static final String MISSING_LLM_ID = "dddd11112222333344445555";
    private static final String PRESENT_DICT_ID = "1111222233334444aaaabbbb";
    private static final String MISSING_DICT_ID = "5555666677778888ccccdddd";

    private static final String BEHAVIOR_URI = "eddi://ai.labs.rules/rulestore/rulesets/" + BEHAVIOR_ID + "?version=1";
    private static final String MISSING_LLM_URI = "eddi://ai.labs.llm/llmstore/llms/" + MISSING_LLM_ID + "?version=1";
    private static final String PRESENT_DICT_URI = "eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + PRESENT_DICT_ID + "?version=1";
    private static final String MISSING_DICT_URI = "eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + MISSING_DICT_ID + "?version=1";

    private IZipArchive zipArchive;
    private IJsonSerialization jsonSerialization;
    private IDocumentDescriptorStore documentDescriptorStore;
    private RestImportService importService;
    private BackupMetrics metrics;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() throws Exception {
        zipArchive = mock(IZipArchive.class);
        jsonSerialization = mock(IJsonSerialization.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        metrics = mock(BackupMetrics.class);
        var templateSyntaxMigrator = mock(TemplateSyntaxMigrator.class);
        when(templateSyntaxMigrator.migrate(anyString())).thenAnswer(inv -> inv.getArgument(0));

        importService = new RestImportService(
                zipArchive, jsonSerialization,
                mock(IMigrationManager.class), documentDescriptorStore,
                templateSyntaxMigrator, mock(StructuralMatcher.class),
                mock(UpgradeExecutor.class), mock(IScheduleStore.class), metrics,
                mock(ResourceAccessGuard.class), mock(SpaceContext.class));

        when(jsonSerialization.deserialize(anyString(), eq(AgentConfiguration.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), AgentConfiguration.class));
        when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), WorkflowConfiguration.class));
        when(jsonSerialization.deserialize(anyString(), eq(DocumentDescriptor.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), DocumentDescriptor.class));
        when(jsonSerialization.deserialize(anyString(), eq(RuleSetConfiguration.class)))
                .thenReturn(new RuleSetConfiguration());
        when(jsonSerialization.deserialize(anyString(), eq(DictionaryConfiguration.class)))
                .thenReturn(new DictionaryConfiguration());
        when(jsonSerialization.serialize(any())).thenAnswer(inv -> mapper.writeValueAsString(inv.getArgument(0)));
    }

    /**
     * The headline case: a selective export that kept the behaviour rules and left
     * the LLM config out. The LLM step cannot be honoured on a create, so it goes —
     * and nothing is written for it.
     */
    @Test
    @DisplayName("a step whose config names a file the archive lacks is dropped, the rest is imported")
    void dropsStepWhoseConfigNamesAMissingFile() throws Exception {
        archiveWithWorkflowSteps("""
                {"type":"eddi://ai.labs.behavior","config":{"uri":"%s"}},
                {"type":"eddi://ai.labs.llm","config":{"uri":"%s"}}
                """.formatted(BEHAVIOR_URI, MISSING_LLM_URI), true);

        var workflowStore = mock(IWorkflowStore.class);
        when(workflowStore.create(any())).thenReturn(resourceId("7777111122223333444455bb", 1));
        var ruleSetStore = mock(IRuleSetStore.class);
        when(ruleSetStore.create(any())).thenReturn(resourceId("1111111122223333444455cc", 1));
        var llmStore = mock(ILlmStore.class);

        var stored = ArgumentCaptor.forClass(WorkflowConfiguration.class);
        try (var cdi = stubCdi(IAgentStore.class, stubAgentCreation(),
                IWorkflowStore.class, workflowStore,
                IRuleSetStore.class, ruleSetStore,
                ILlmStore.class, llmStore)) {
            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "create", null, null, null);

            assertEquals(201, response.getStatus(),
                    "an archive EDDI's own selective export wrote must import, not 400");
        }

        verify(workflowStore).create(stored.capture());
        List<WorkflowConfiguration.WorkflowStep> steps = stored.getValue().getWorkflowSteps();
        assertEquals(1, steps.size(), "only the unresolvable step may go, kept: " + stepTypes(steps));
        assertEquals("eddi://ai.labs.behavior", steps.getFirst().getType().toString());
        // Creating the LLM anyway would have written a resource nothing references.
        verify(llmStore, never()).create(any(LlmConfiguration.class));
    }

    /**
     * A missing reference reached through a list inside the step's config drops the
     * step too.
     */
    @Test
    @DisplayName("a missing reference nested in a list under config still drops the step")
    void dropsStepWhenMissingReferenceIsNestedInAList() throws Exception {
        // The kept step also carries a list, of references the archive does carry:
        // a list is only a reason to drop when something in it is actually absent.
        archiveWithWorkflowSteps("""
                {"type":"eddi://ai.labs.behavior","config":{"uri":"%s","fallbacks":[{"uri":"%s"}]}},
                {"type":"eddi://ai.labs.llm","config":{"models":[{"uri":"%s"}]}}
                """.formatted(BEHAVIOR_URI, BEHAVIOR_URI, MISSING_LLM_URI), true);

        var workflowStore = mock(IWorkflowStore.class);
        when(workflowStore.create(any())).thenReturn(resourceId("7777111122223333444455bb", 1));
        var ruleSetStore = mock(IRuleSetStore.class);
        when(ruleSetStore.create(any())).thenReturn(resourceId("1111111122223333444455cc", 1));

        var stored = ArgumentCaptor.forClass(WorkflowConfiguration.class);
        try (var cdi = stubCdi(IAgentStore.class, stubAgentCreation(),
                IWorkflowStore.class, workflowStore,
                IRuleSetStore.class, ruleSetStore)) {
            importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
        }

        verify(workflowStore).create(stored.capture());
        List<WorkflowConfiguration.WorkflowStep> steps = stored.getValue().getWorkflowSteps();
        assertEquals(1, steps.size(), "kept: " + stepTypes(steps));
        assertEquals("eddi://ai.labs.behavior", steps.getFirst().getType().toString());
    }

    /**
     * A parser's regular dictionaries are a list of nested references. Losing one
     * of them must cost the list entry, never the parser step — dropping the parser
     * would leave the agent unable to understand any input at all.
     */
    @Test
    @DisplayName("a missing entry in a nested extensions list is pruned, its step survives")
    void prunesOnlyTheMissingEntryFromANestedList() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            File versionDir = versionDir(dir);
            Files.writeString(new File(versionDir, WORKFLOW_ID + ".workflow.json").toPath(), """
                    {"workflowSteps":[{"type":"eddi://ai.labs.parser","extensions":{"dictionaries":[
                      {"config":{"uri":"%s"}},
                      {"config":{"uri":"%s"}}]}}]}
                    """.formatted(MISSING_DICT_URI, PRESENT_DICT_URI));
            Files.writeString(new File(versionDir, PRESENT_DICT_ID + ".regulardictionary.json").toPath(),
                    "{\"words\":[]}");
        });

        var workflowStore = mock(IWorkflowStore.class);
        when(workflowStore.create(any())).thenReturn(resourceId("7777111122223333444455bb", 1));
        var dictionaryStore = mock(IDictionaryStore.class);
        when(dictionaryStore.create(any())).thenReturn(resourceId("2222333344445555aaaabbbb", 1));

        var stored = ArgumentCaptor.forClass(WorkflowConfiguration.class);
        try (var cdi = stubCdi(IAgentStore.class, stubAgentCreation(),
                IWorkflowStore.class, workflowStore,
                IDictionaryStore.class, dictionaryStore)) {
            importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
        }

        verify(workflowStore).create(stored.capture());
        List<WorkflowConfiguration.WorkflowStep> steps = stored.getValue().getWorkflowSteps();
        assertEquals(1, steps.size(), "the parser step must survive losing one dictionary");
        Object dictionaries = steps.getFirst().getExtensions().get("dictionaries");
        assertTrue(dictionaries instanceof List<?> list && list.size() == 1,
                "only the missing dictionary may be pruned, was " + dictionaries);
        // The dictionary the archive does carry is still imported.
        verify(dictionaryStore).create(any());
    }

    /**
     * Jackson turns a {@code null} element in the archived step list into a null
     * entry. It has to go with the rewrite rather than be serialized back into the
     * stored workflow, where the engine would meet it on the next turn.
     */
    @Test
    @DisplayName("a null step entry does not survive the rewrite")
    void nullStepEntryDoesNotSurviveTheRewrite() throws Exception {
        archiveWithWorkflowSteps("""
                null,
                {"type":"eddi://ai.labs.behavior","config":{"uri":"%s"}},
                {"type":"eddi://ai.labs.llm","config":{"uri":"%s"}}
                """.formatted(BEHAVIOR_URI, MISSING_LLM_URI), true);

        var workflowStore = mock(IWorkflowStore.class);
        when(workflowStore.create(any())).thenReturn(resourceId("7777111122223333444455bb", 1));
        var ruleSetStore = mock(IRuleSetStore.class);
        when(ruleSetStore.create(any())).thenReturn(resourceId("1111111122223333444455cc", 1));

        var stored = ArgumentCaptor.forClass(WorkflowConfiguration.class);
        try (var cdi = stubCdi(IAgentStore.class, stubAgentCreation(),
                IWorkflowStore.class, workflowStore,
                IRuleSetStore.class, ruleSetStore)) {
            importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
        }

        verify(workflowStore).create(stored.capture());
        List<WorkflowConfiguration.WorkflowStep> steps = stored.getValue().getWorkflowSteps();
        assertEquals(1, steps.size(), "kept: " + stepTypes(steps));
        assertEquals("eddi://ai.labs.behavior", steps.getFirst().getType().toString());
    }

    /**
     * A workflow with nothing to prune must reach the store as the <em>archive
     * wrote it</em>, not as this deployment's model would re-emit it.
     * Re-serializing every archived workflow on every import is how a field the
     * model does not know gets silently dropped, so the pass-through branch is
     * asserted on the text: the exact string the archive carried is the one that
     * goes on to be stored, and nothing was ever handed back to
     * {@code jsonSerialization.serialize} for a rewrite.
     * <p>
     * The archived JSON below is deliberately spaced the way no serializer would
     * emit it — an assertion on the parsed result cannot tell a pass-through from a
     * round-trip, because {@code {"workflowSteps":null}} parses to null steps
     * either way.
     */
    @Test
    @DisplayName("a workflow with no step list is passed through verbatim, not re-serialized")
    void workflowWithoutStepsIsPassedThroughUnchanged() throws Exception {
        String archived = "{ \"workflowSteps\" : null }";
        stubUnzip(dir -> {
            writeAgent(dir);
            Files.writeString(new File(versionDir(dir), WORKFLOW_ID + ".workflow.json").toPath(), archived);
        });

        var workflowStore = mock(IWorkflowStore.class);
        when(workflowStore.create(any())).thenReturn(resourceId("7777111122223333444455bb", 1));

        var stored = ArgumentCaptor.forClass(WorkflowConfiguration.class);
        try (var cdi = stubCdi(IAgentStore.class, stubAgentCreation(), IWorkflowStore.class, workflowStore)) {
            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "create", null, null, null);
            assertEquals(201, response.getStatus());
        }

        verify(workflowStore).create(stored.capture());
        assertNull(stored.getValue().getWorkflowSteps(),
                "an explicit null step list must reach the store as it was archived");

        // Every workflow string this import parsed is still the archive's, character
        // for character — an always-rewrite would hand on the serializer's rendering
        // instead. How many times the import parses it is its own business, so the
        // assertion is over all of them rather than on a count.
        var workflowText = ArgumentCaptor.forClass(String.class);
        verify(jsonSerialization, atLeastOnce())
                .deserialize(workflowText.capture(), eq(WorkflowConfiguration.class));
        assertTrue(workflowText.getAllValues().stream().allMatch(archived::equals),
                "the pruner must hand back the archived text untouched, not this model's re-rendering, was: "
                        + workflowText.getAllValues());
        verify(jsonSerialization, never()).serialize(any(WorkflowConfiguration.class));
    }

    /**
     * The same promise for the branch that carries it in the common case: a
     * workflow whose every reference the archive <em>does</em> satisfy has nothing
     * dropped, so the pruner hands back the string it was given rather than
     * re-emitting the parsed model.
     * <p>
     * Here the text cannot be compared whole — the import rewrites the behaviour
     * URI to the id it just created — so the assertion is that the archive's own
     * spacing survived into the text the import went on to store, and that the
     * workflow was never handed back to the serializer at all.
     */
    @Test
    @DisplayName("a workflow with nothing to prune is passed through, not re-serialized")
    void workflowWithNothingToPruneIsPassedThroughUnchanged() throws Exception {
        String archived = "{ \"workflowSteps\" : [ {\"type\":\"eddi://ai.labs.behavior\","
                + "\"config\":{\"uri\":\"" + BEHAVIOR_URI + "\"}} ] }";
        stubUnzip(dir -> {
            writeAgent(dir);
            File versionDir = versionDir(dir);
            Files.writeString(new File(versionDir, WORKFLOW_ID + ".workflow.json").toPath(), archived);
            Files.writeString(new File(versionDir, BEHAVIOR_ID + ".behavior.json").toPath(),
                    "{\"behaviorGroups\":[]}");
        });

        var workflowStore = mock(IWorkflowStore.class);
        when(workflowStore.create(any())).thenReturn(resourceId("7777111122223333444455bb", 1));
        var ruleSetStore = mock(IRuleSetStore.class);
        when(ruleSetStore.create(any())).thenReturn(resourceId("1111111122223333444455cc", 1));

        var stored = ArgumentCaptor.forClass(WorkflowConfiguration.class);
        try (var cdi = stubCdi(IAgentStore.class, stubAgentCreation(),
                IWorkflowStore.class, workflowStore,
                IRuleSetStore.class, ruleSetStore)) {
            importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
        }

        verify(workflowStore).create(stored.capture());
        assertEquals(1, stored.getValue().getWorkflowSteps().size(), "nothing was unresolvable, so nothing may go");

        var workflowText = ArgumentCaptor.forClass(String.class);
        verify(jsonSerialization, atLeastOnce())
                .deserialize(workflowText.capture(), eq(WorkflowConfiguration.class));
        String storedText = workflowText.getAllValues().getLast();
        assertTrue(storedText.startsWith("{ \"workflowSteps\" : [ "),
                "the archive's own text must survive the pruner, was: " + storedText);
        verify(jsonSerialization, never()).serialize(any(WorkflowConfiguration.class));
    }

    /**
     * Two URI shapes the pruner must refuse to judge: one it cannot parse at all,
     * and one whose authority names a resource kind the backup package does not
     * move. Guessing "missing" for either would silently delete a working step.
     */
    @Test
    @DisplayName("an unparseable URI and an unknown resource type are never treated as missing")
    void unparseableAndUnknownUrisAreNeverTreatedAsMissing() throws Exception {
        archiveWithWorkflowSteps("""
                {"type":"eddi://ai.labs.parser","config":{"uri":"http://exa mple.com/parser"}},
                {"type":"eddi://ai.labs.normalizer","config":{"uri":"eddi://ai.labs.unknown/store/things/%s?version=1"}},
                {"type":"eddi://ai.labs.llm","config":{"uri":"%s"}}
                """.formatted(BEHAVIOR_ID, MISSING_LLM_URI), false);

        var workflowStore = mock(IWorkflowStore.class);
        when(workflowStore.create(any())).thenReturn(resourceId("7777111122223333444455bb", 1));

        var stored = ArgumentCaptor.forClass(WorkflowConfiguration.class);
        try (var cdi = stubCdi(IAgentStore.class, stubAgentCreation(), IWorkflowStore.class, workflowStore)) {
            importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
        }

        verify(workflowStore).create(stored.capture());
        List<WorkflowConfiguration.WorkflowStep> steps = stored.getValue().getWorkflowSteps();
        assertEquals(2, steps.size(), "only the genuinely unresolvable step may go, kept: " + stepTypes(steps));
        assertEquals("eddi://ai.labs.parser", steps.get(0).getType().toString());
        assertEquals("eddi://ai.labs.normalizer", steps.get(1).getType().toString());
    }

    /**
     * The recursion guard is a real limit. A reference buried deeper than it goes
     * is not pruned — and because the URI scan that follows works on raw text, that
     * import fails, naming the resource and the strategy that can answer it. Better
     * a message the operator can act on than an unbounded walk.
     */
    @Test
    @DisplayName("a reference deeper than the recursion guard fails the import, pointing at strategy=merge")
    void referenceDeeperThanTheGuardFailsWithAnActionableMessage() throws Exception {
        StringBuilder deepConfig = new StringBuilder();
        StringBuilder deepExtensions = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            deepConfig.append("{\"level").append(i).append("\":");
            deepExtensions.append("{\"level").append(i).append("\":");
        }
        deepConfig.append("{\"uri\":\"").append(MISSING_LLM_URI).append("\"}");
        deepExtensions.append("{\"uri\":\"").append(MISSING_LLM_URI).append("\"}");
        deepConfig.append("}".repeat(12));
        deepExtensions.append("}".repeat(12));

        archiveWithWorkflowSteps("""
                {"type":"eddi://ai.labs.llm","config":%s},
                {"type":"eddi://ai.labs.llm","extensions":%s}
                """.formatted(deepConfig, deepExtensions), false);

        var thrown = assertThrows(BadRequestException.class, () -> {
            try (var cdi = stubCdi(IAgentStore.class, stubAgentCreation(),
                    IWorkflowStore.class, mock(IWorkflowStore.class))) {
                importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
            }
        });

        assertTrue(thrown.getMessage().contains("strategy=merge"),
                "the operator must be told which strategy can answer the reference, was: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(MISSING_LLM_ID),
                "the message must name the resource, was: " + thrown.getMessage());
    }

    /**
     * The preview walks the agent's workflow list before anything is written, and a
     * {@code null} entry there — which Jackson produces from
     * {@code "workflows":[null]} — has no resource id to look anything up by. It is
     * skipped, so the operator still sees the rows the archive can actually deliver
     * rather than a 500 with nothing to act on.
     */
    @Test
    @DisplayName("a null workflow reference is skipped by the preview, not fatal to it")
    void nullWorkflowReferenceIsSkippedByThePreview() throws Exception {
        stubUnzip(dir -> {
            Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(),
                    "{\"workflows\":[null,\"eddi://ai.labs.workflow/workflowstore/workflows/"
                            + WORKFLOW_ID + "?version=1\"]}");
            Files.writeString(new File(versionDir(dir), WORKFLOW_ID + ".workflow.json").toPath(),
                    "{\"workflowSteps\":[]}");
        });

        ImportPreview preview = importService.previewImport(new ByteArrayInputStream(new byte[0]), null);

        assertEquals(AGENT_ORIGIN_ID, preview.sourceAgentId());
        List<String> workflowRows = preview.resources().stream()
                .filter(diff -> "workflow".equals(diff.resourceType()))
                .map(ImportPreview.ResourceDiff::sourceId)
                .toList();
        assertEquals(List.of(WORKFLOW_ID), workflowRows,
                "the null entry cannot become a row, and must not cost the one that can");
    }

    // ==================== Helpers ====================

    private static String stepTypes(List<WorkflowConfiguration.WorkflowStep> steps) {
        return steps.stream().map(step -> step == null ? "null" : String.valueOf(step.getType())).toList().toString();
    }

    private void archiveWithWorkflowSteps(String stepsJson, boolean withBehaviorFile) throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            File versionDir = versionDir(dir);
            Files.writeString(new File(versionDir, WORKFLOW_ID + ".workflow.json").toPath(),
                    "{\"workflowSteps\":[" + stepsJson + "]}");
            if (withBehaviorFile) {
                Files.writeString(new File(versionDir, BEHAVIOR_ID + ".behavior.json").toPath(),
                        "{\"behaviorGroups\":[]}");
            }
        });
    }

    private static void writeAgent(File dir) throws IOException {
        Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(),
                "{\"workflows\":[\"eddi://ai.labs.workflow/workflowstore/workflows/"
                        + WORKFLOW_ID + "?version=1\"]}");
    }

    private static File versionDir(File dir) {
        File versionDir = new File(dir, WORKFLOW_ID + "/1");
        assertTrue(versionDir.mkdirs() || versionDir.isDirectory());
        return versionDir;
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
