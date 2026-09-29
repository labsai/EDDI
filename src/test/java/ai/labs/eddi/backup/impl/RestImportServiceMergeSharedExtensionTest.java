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
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.InternalServerErrorException;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A merge can update one resource more than once: two workflows that share a
 * dictionary each carry it in the archive, and each merges it. The rollback of
 * a failed merge must still bring back the content from <em>before</em> the
 * import — not the intermediate version the first workflow's update wrote.
 */
class RestImportServiceMergeSharedExtensionTest {

    private static final String AGENT_ORIGIN_ID = "aaaa11112222333344445555";
    private static final String WORKFLOW_1 = "bbbb11112222333344445551";
    private static final String WORKFLOW_2 = "bbbb11112222333344445552";
    private static final String DICT_ORIGIN_ID = "dddd11112222333344445555";
    private static final String LOCAL_DICT_ID = "eeee11112222333344445555";
    private static final String DICT_URI_IN_ARCHIVE = "eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + DICT_ORIGIN_ID
            + "?version=1";

    private IZipArchive zipArchive;
    private IJsonSerialization jsonSerialization;
    private IDocumentDescriptorStore documentDescriptorStore;
    private TemplateSyntaxMigrator templateSyntaxMigrator;
    private RestImportService importService;

    @BeforeEach
    void setUp() {
        zipArchive = mock(IZipArchive.class);
        jsonSerialization = mock(IJsonSerialization.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        templateSyntaxMigrator = mock(TemplateSyntaxMigrator.class);
        when(templateSyntaxMigrator.migrate(anyString())).thenAnswer(i -> i.getArgument(0));

        importService = new RestImportService(zipArchive, jsonSerialization, mock(IMigrationManager.class), documentDescriptorStore,
                templateSyntaxMigrator, mock(StructuralMatcher.class), mock(UpgradeExecutor.class), mock(IScheduleStore.class),
                mock(BackupMetrics.class), mock(ResourceAccessGuard.class), mock(SpaceContext.class),
                mock(RagSourceIngestionService.class), true, false, Optional.empty());
    }

    @Test
    @DisplayName("a dictionary merged through two workflows is restored to its pre-import content and name")
    void sharedExtensionIsRestoredToItsOriginal() throws Exception {
        stubTwoWorkflowsSharingOneDictionary();

        // The local dictionary: v1 holds the original. A tiny versioned store: an
        // update
        // must address the current version, exactly as HistorizedResourceStore demands.
        var original = new DictionaryConfiguration();
        var intermediate = new DictionaryConfiguration();
        var dictionaryStore = mock(IDictionaryStore.class);
        when(dictionaryStore.read(LOCAL_DICT_ID, 1)).thenReturn(original);
        when(dictionaryStore.read(LOCAL_DICT_ID, 2)).thenReturn(intermediate);
        var current = new AtomicInteger(1);
        List<Object> written = new ArrayList<>();
        var restDictionaryStore = mock(IRestDictionaryStore.class);
        when(restDictionaryStore.updateRegularDictionary(eq(LOCAL_DICT_ID), anyInt(), any())).thenAnswer(i -> {
            int version = i.getArgument(1);
            if (version != current.get()) {
                throw new WebApplicationException(Response.status(409).build());
            }
            current.incrementAndGet();
            written.add(i.getArgument(2));
            return Response.ok().build();
        });

        // The second workflow finds the dictionary at the version the first one wrote.
        when(documentDescriptorStore.findByOriginId(DICT_ORIGIN_ID)).thenAnswer(i -> List.of(localDescriptorAt(current.get())));
        when(documentDescriptorStore.getCurrentResourceId(LOCAL_DICT_ID)).thenReturn(resourceId(LOCAL_DICT_ID, 1));
        var originalDescriptor = new DocumentDescriptor();
        originalDescriptor.setName("Original name");
        when(documentDescriptorStore.readDescriptor(LOCAL_DICT_ID, 1)).thenReturn(originalDescriptor, new DocumentDescriptor(),
                new DocumentDescriptor(), new DocumentDescriptor(), new DocumentDescriptor(), new DocumentDescriptor());

        var workflowStore = mock(IWorkflowStore.class);
        when(workflowStore.create(any())).thenReturn(resourceId("cccc11112222333344445551", 1),
                resourceId("cccc11112222333344445552", 1));
        var agentStore = mock(IAgentStore.class);
        when(agentStore.create(any())).thenThrow(new IResourceStore.ResourceStoreException("agent store unavailable"));

        try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
            CDI cdi = mock(CDI.class);
            cdiMock.when(CDI::current).thenReturn(cdi);
            stubBean(cdi, IWorkflowStore.class, workflowStore);
            stubBean(cdi, IAgentStore.class, agentStore);
            stubBean(cdi, IDictionaryStore.class, dictionaryStore);
            stubBean(cdi, IRestDictionaryStore.class, restDictionaryStore);
            stubBean(cdi, CapabilityRegistryService.class, mock(CapabilityRegistryService.class));

            assertThrows(InternalServerErrorException.class, () -> importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "merge", null, null, null));
        }

        // v1 -> v2 (workflow 1), v2 -> v3 (workflow 2), and the rollback v3 -> v4
        assertEquals(4, current.get(), "the rollback must land on top of the latest imported version");
        assertEquals(3, written.size());
        assertSame(original, written.get(2), "restored to the content from before the import, not the intermediate");
        verify(documentDescriptorStore).setDescriptor(eq(LOCAL_DICT_ID), eq(1), argThat((DocumentDescriptor d) -> d == originalDescriptor
                && "Original name".equals(d.getName())
                && d.getResource().toString().endsWith(LOCAL_DICT_ID + "?version=4")));
    }

    @Test
    @DisplayName("a merge that cannot read a resource before its first update aborts before writing it, and rolls back the rest")
    void unreadableResourceAbortsTheMergeBeforeItsUpdate() throws Exception {
        assertMergeAbortsBeforeUpdatingAnUnreadableDictionary(true);
    }

    @Test
    @DisplayName("a store read that yields nothing counts as unreadable, too")
    void emptyReadAbortsTheMergeBeforeItsUpdate() throws Exception {
        assertMergeAbortsBeforeUpdatingAnUnreadableDictionary(false);
    }

    private void assertMergeAbortsBeforeUpdatingAnUnreadableDictionary(boolean readThrows) throws Exception {
        String dictOriginB = "dddd11112222333344445556";
        String localDictB = "eeee11112222333344445556";
        stubWorkflowsWithOwnDictionaries(List.of(WORKFLOW_1, WORKFLOW_2), List.of(DICT_ORIGIN_ID, dictOriginB));

        // Dictionary A is readable and merged by the first workflow.
        var originalA = new DictionaryConfiguration();
        var dictionaryStore = mock(IDictionaryStore.class);
        when(dictionaryStore.read(LOCAL_DICT_ID, 1)).thenReturn(originalA);
        // Dictionary B cannot be read: nothing would restore it.
        if (readThrows) {
            when(dictionaryStore.read(localDictB, 1)).thenThrow(new IResourceStore.ResourceStoreException("read timed out"));
        } else {
            when(dictionaryStore.read(localDictB, 1)).thenReturn(null);
        }

        var currentA = new AtomicInteger(1);
        List<Object> writtenA = new ArrayList<>();
        var restDictionaryStore = mock(IRestDictionaryStore.class);
        when(restDictionaryStore.updateRegularDictionary(eq(LOCAL_DICT_ID), anyInt(), any())).thenAnswer(i -> {
            if ((int) i.getArgument(1) != currentA.get()) {
                throw new WebApplicationException(Response.status(409).build());
            }
            currentA.incrementAndGet();
            writtenA.add(i.getArgument(2));
            return Response.ok().build();
        });

        when(documentDescriptorStore.findByOriginId(DICT_ORIGIN_ID)).thenAnswer(i -> List.of(localDescriptorAt(LOCAL_DICT_ID, currentA.get())));
        when(documentDescriptorStore.findByOriginId(dictOriginB)).thenAnswer(i -> List.of(localDescriptorAt(localDictB, 1)));
        when(documentDescriptorStore.getCurrentResourceId(anyString())).thenAnswer(i -> resourceId(i.getArgument(0), 1));
        when(documentDescriptorStore.readDescriptor(anyString(), anyInt())).thenAnswer(i -> new DocumentDescriptor());

        var workflowStore = mock(IWorkflowStore.class);
        when(workflowStore.create(any())).thenReturn(resourceId("cccc11112222333344445551", 1),
                resourceId("cccc11112222333344445552", 1));
        var agentStore = mock(IAgentStore.class);

        InternalServerErrorException failure;
        try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
            CDI cdi = mock(CDI.class);
            cdiMock.when(CDI::current).thenReturn(cdi);
            stubBean(cdi, IWorkflowStore.class, workflowStore);
            stubBean(cdi, IAgentStore.class, agentStore);
            stubBean(cdi, IDictionaryStore.class, dictionaryStore);
            stubBean(cdi, IRestDictionaryStore.class, restDictionaryStore);
            stubBean(cdi, CapabilityRegistryService.class, mock(CapabilityRegistryService.class));

            failure = assertThrows(InternalServerErrorException.class, () -> importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "merge", null, null, null));
        }

        // B was never written: an update with no snapshot would survive the rollback.
        verify(restDictionaryStore, never()).updateRegularDictionary(eq(localDictB), anyInt(), any());
        // A, merged before the abort, is written back to its pre-import content.
        assertEquals(2, writtenA.size(), "the merge update and its restore");
        assertSame(originalA, writtenA.get(1), "the rollback restores what the merge had already updated");
        // Nothing past the abort ran.
        verify(agentStore, never()).create(any());
        assertTrue(failure.getMessage().contains(localDictB) && failure.getMessage().contains("could not be read"),
                failure.getMessage());
    }

    private static DocumentDescriptor localDescriptorAt(String localId, int version) {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + localId + "?version=" + version));
        return descriptor;
    }

    private void stubWorkflowsWithOwnDictionaries(List<String> workflowIds, List<String> dictOriginIds) throws Exception {
        doAnswer(inv -> {
            File dir = inv.getArgument(1);
            dir.mkdirs();
            Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "AGENTJSON");
            for (int n = 0; n < workflowIds.size(); n++) {
                String workflowId = workflowIds.get(n);
                String dictOrigin = dictOriginIds.get(n);
                File workflowDir = new File(new File(dir, workflowId), "1");
                workflowDir.mkdirs();
                String dictUri = "eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + dictOrigin + "?version=1";
                Files.writeString(new File(workflowDir, workflowId + ".workflow.json").toPath(),
                        "{\"workflowSteps\":[{\"config\":{\"uri\":\"" + dictUri + "\"}}]}");
                Files.writeString(new File(workflowDir, dictOrigin + ".regulardictionary.json").toPath(), "DICTJSON");
            }
            return null;
        }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(workflowIds.stream()
                .map(id -> URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + id + "?version=1")).toList());
        when(jsonSerialization.deserialize(eq("AGENTJSON"), eq(AgentConfiguration.class))).thenReturn(agentConfig);
        when(jsonSerialization.deserialize(eq("DICTJSON"), eq(DictionaryConfiguration.class))).thenAnswer(i -> new DictionaryConfiguration());
        when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class))).thenAnswer(i -> new WorkflowConfiguration());
    }

    private static DocumentDescriptor localDescriptorAt(int version) {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + LOCAL_DICT_ID + "?version=" + version));
        return descriptor;
    }

    private void stubTwoWorkflowsSharingOneDictionary() throws Exception {
        String workflowJson = "{\"workflowSteps\":[{\"config\":{\"uri\":\"" + DICT_URI_IN_ARCHIVE + "\"}}]}";
        doAnswer(inv -> {
            File dir = inv.getArgument(1);
            dir.mkdirs();
            Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "AGENTJSON");
            for (String workflowId : List.of(WORKFLOW_1, WORKFLOW_2)) {
                File workflowDir = new File(new File(dir, workflowId), "1");
                workflowDir.mkdirs();
                Files.writeString(new File(workflowDir, workflowId + ".workflow.json").toPath(), workflowJson);
                Files.writeString(new File(workflowDir, DICT_ORIGIN_ID + ".regulardictionary.json").toPath(), "DICTJSON");
            }
            return null;
        }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(
                URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_1 + "?version=1"),
                URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_2 + "?version=1")));
        when(jsonSerialization.deserialize(eq("AGENTJSON"), eq(AgentConfiguration.class))).thenReturn(agentConfig);
        when(jsonSerialization.deserialize(eq("DICTJSON"), eq(DictionaryConfiguration.class))).thenAnswer(i -> new DictionaryConfiguration());
        when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class))).thenAnswer(i -> new WorkflowConfiguration());
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
