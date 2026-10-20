/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.backup.model.UpgradeResult;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IPromotionImpactReader;
import ai.labs.eddi.engine.model.PromotionImpact;
import ai.labs.eddi.engine.schedule.IRestScheduleStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An import or a sync onto an existing agent writes a new agent version.
 * Whether that version is <em>compatible</em> with the one it replaces decides
 * if the conversations running on the target follow it or stay pinned, so it is
 * the operator's declaration, exactly as for a normal save, and never assumed:
 * breaking is the default and {@code compatible=true} must be asked for.
 * <p>
 * The merge rollback is the subtle part. It writes the pre-import content back
 * through the same agent write, so it must carry the same declaration: with
 * nothing bumped by a compatible import, a breaking restore would pin the
 * conversations that had already followed onto the aborted import's content.
 */
@DisplayName("RestImportService — declaring an import compatible")
class RestImportServiceCompatibleTest {

    private static final String AGENT_ORIGIN_ID = "aabb11112222333344445555";
    private static final String TARGET_AGENT_ID = "ccdd11112222333344445555";

    private IZipArchive zipArchive;
    private IJsonSerialization jsonSerialization;
    private IDocumentDescriptorStore documentDescriptorStore;
    private StructuralMatcher structuralMatcher;
    private UpgradeExecutor upgradeExecutor;
    private IRestScheduleStore restScheduleStore;
    private IRestAgentStore restAgentStore;
    private RestImportService importService;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() throws Exception {
        zipArchive = mock(IZipArchive.class);
        jsonSerialization = mock(IJsonSerialization.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        structuralMatcher = mock(StructuralMatcher.class);
        upgradeExecutor = mock(UpgradeExecutor.class);
        restScheduleStore = mock(IRestScheduleStore.class);
        restAgentStore = mock(IRestAgentStore.class);
        when(restAgentStore.updateAgent(anyString(), anyInt(), any(), any())).thenReturn(Response.ok().build());

        var templateSyntaxMigrator = mock(TemplateSyntaxMigrator.class);
        when(templateSyntaxMigrator.migrate(anyString())).thenAnswer(inv -> inv.getArgument(0));

        importService = new RestImportService(
                zipArchive, jsonSerialization,
                mock(IMigrationManager.class), documentDescriptorStore,
                templateSyntaxMigrator, structuralMatcher,
                upgradeExecutor, mock(IScheduleStore.class), mock(BackupMetrics.class),
                mock(ResourceAccessGuard.class), mock(SpaceContext.class), mock(RagSourceIngestionService.class),
                true, false, Optional.empty());

        when(jsonSerialization.deserialize(anyString(), eq(AgentConfiguration.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), AgentConfiguration.class));
        when(jsonSerialization.deserialize(anyString(), eq(DocumentDescriptor.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), DocumentDescriptor.class));
        when(jsonSerialization.deserialize(anyString(), eq(ScheduleConfiguration.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), ScheduleConfiguration.class));
    }

    // ==================== strategy=merge ====================

    @Nested
    @DisplayName("strategy=merge")
    class Merge {

        @Test
        @DisplayName("is breaking unless declared compatible")
        void breakingByDefault() throws Exception {
            stubArchive(false);
            stubExistingAgent();

            assertEquals(201, merge(null).getStatus());
            assertEquals(201, mergeLegacyForm().getStatus());
            assertEquals(201, merge(false).getStatus());

            verify(restAgentStore, times(3)).updateAgent(eq(TARGET_AGENT_ID), eq(4), any(), eq(false));
            verify(restAgentStore, never()).updateAgent(anyString(), anyInt(), any(), eq(true));
        }

        @Test
        @DisplayName("declared compatible, the agent is written compatible")
        void compatibleWhenDeclared() throws Exception {
            stubArchive(false);
            stubExistingAgent();

            var written = new AgentConfiguration();
            written.setCompatibilityGeneration(2);
            when(agentStore.read(TARGET_AGENT_ID, 5)).thenReturn(written);

            Response response = merge(true);

            assertEquals(201, response.getStatus());
            // what the declaration did, so a script need not make a second call
            assertEquals("true", response.getHeaderString(RestImportService.HEADER_AGENT_COMPATIBLE));
            assertEquals("2", response.getHeaderString(RestImportService.HEADER_AGENT_COMPATIBILITY_GENERATION));
            verify(restAgentStore).updateAgent(eq(TARGET_AGENT_ID), eq(4), any(), eq(true));
            verify(restAgentStore, never()).updateAgent(anyString(), anyInt(), any(), eq(false));
        }

        @Test
        @DisplayName("a failed breaking merge restores the original as a breaking version too")
        void failedBreakingMergeRestoresBreaking() throws Exception {
            stubArchive(true);
            stubExistingAgent();

            var thrown = assertThrows(WebApplicationException.class, () -> merge(false));

            assertEquals(400, thrown.getResponse().getStatus());
            // the aborted import: v4 -> v5, one generation up ...
            verify(restAgentStore).updateAgent(eq(TARGET_AGENT_ID), eq(4), any(), eq(false));
            // ... and the restore: v5 -> v6, up again, so no later breaking save can
            // share a generation with the aborted version
            verify(restAgentStore).updateAgent(eq(TARGET_AGENT_ID), eq(5), any(), eq(false));
            verify(restAgentStore, never()).updateAgent(anyString(), anyInt(), any(), eq(true));
        }

        @Test
        @DisplayName("a failed compatible merge restores the original as a compatible version too")
        void failedCompatibleMergeRestoresCompatible() throws Exception {
            stubArchive(true);
            stubExistingAgent();
            var previous = new AgentConfiguration();
            previous.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aaaa11112222333344445555?version=1")));
            when(agentStore.read(TARGET_AGENT_ID, 4)).thenReturn(previous);

            var thrown = assertThrows(WebApplicationException.class, () -> merge(true));

            assertEquals(400, thrown.getResponse().getStatus());
            verify(restAgentStore).updateAgent(eq(TARGET_AGENT_ID), eq(4), any(), eq(true));
            // Nothing bumped the generation, so the restore keeps it as well: conversations
            // that followed onto the aborted version follow on to the restored content.
            // Written breaking, it would pin them to the aborted import's content.
            var restored = ArgumentCaptor.forClass(AgentConfiguration.class);
            verify(restAgentStore).updateAgent(eq(TARGET_AGENT_ID), eq(5), restored.capture(), eq(true));
            assertSame(previous, restored.getValue(), "the restore writes the pre-import content");
            verify(restAgentStore, never()).updateAgent(anyString(), anyInt(), any(), eq(false));
        }

        @Test
        @DisplayName("compatible has no effect when the agent is created")
        void compatibleDoesNotTouchACreate() throws Exception {
            stubArchive(false);
            var created = mock(IAgentStore.class);
            when(created.create(any())).thenReturn(resourceId(TARGET_AGENT_ID, 1));
            when(documentDescriptorStore.getCurrentResourceId(TARGET_AGENT_ID)).thenReturn(resourceId(TARGET_AGENT_ID, 1));
            when(documentDescriptorStore.readDescriptor(TARGET_AGENT_ID, 1)).thenReturn(new DocumentDescriptor());
            agentStore = created;

            try (var ignored = stubCdi()) {
                assertEquals(201, importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null, true)
                        .getStatus());
            }

            verify(restAgentStore, never()).updateAgent(anyString(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("a merge that creates the agent reports no compatibility headers")
        void mergeCreatingTheAgentReportsNoDeclaration() throws Exception {
            stubArchive(false);
            var created = mock(IAgentStore.class);
            when(created.create(any())).thenReturn(resourceId(TARGET_AGENT_ID, 1));
            var newAgent = new AgentConfiguration();
            newAgent.setCompatibilityGeneration(1);
            when(created.read(TARGET_AGENT_ID, 1)).thenReturn(newAgent);
            when(documentDescriptorStore.getCurrentResourceId(TARGET_AGENT_ID)).thenReturn(resourceId(TARGET_AGENT_ID, 1));
            when(documentDescriptorStore.readDescriptor(TARGET_AGENT_ID, 1)).thenReturn(new DocumentDescriptor());
            agentStore = created;

            Response response = merge(true);

            assertEquals(201, response.getStatus());
            // compatible has no effect on a creation, so the answer must not claim one
            assertNull(response.getHeaderString(RestImportService.HEADER_AGENT_COMPATIBLE));
            assertNull(response.getHeaderString(RestImportService.HEADER_AGENT_COMPATIBILITY_GENERATION));
            verify(restAgentStore, never()).updateAgent(anyString(), anyInt(), any(), any());
        }

        private Response merge(Boolean compatible) {
            try (var ignored = stubCdi()) {
                return importService.importAgent(new ByteArrayInputStream(new byte[0]), "merge", null, null, null, compatible);
            }
        }

        /**
         * The five-argument form every caller had before {@code compatible} existed.
         */
        private Response mergeLegacyForm() {
            try (var ignored = stubCdi()) {
                return importService.importAgent(new ByteArrayInputStream(new byte[0]), "merge", null, null, null);
            }
        }
    }

    // ==================== strategy=upgrade ====================

    @Nested
    @DisplayName("strategy=upgrade")
    class Upgrade {

        @Test
        @DisplayName("passes the declaration to the upgrade, breaking by default")
        void declarationReachesTheUpgrade() throws Exception {
            stubEmptyZip();
            when(upgradeExecutor.executeUpgrade(any(), eq(TARGET_AGENT_ID), any(), any(), anyBoolean())).thenReturn(written());

            importService.importAgent(new ByteArrayInputStream(new byte[0]), "upgrade", null, TARGET_AGENT_ID, null, true);
            verify(upgradeExecutor).executeUpgrade(any(), eq(TARGET_AGENT_ID), any(), any(), eq(true));
            verify(upgradeExecutor, never()).executeUpgrade(any(), anyString(), any(), any(), eq(false));

            importService.importAgent(new ByteArrayInputStream(new byte[0]), "upgrade", null, TARGET_AGENT_ID, null, null);
            importService.importAgent(new ByteArrayInputStream(new byte[0]), "upgrade", null, TARGET_AGENT_ID, null);
            verify(upgradeExecutor, times(2)).executeUpgrade(any(), eq(TARGET_AGENT_ID), any(), any(), eq(false));
        }

        private UpgradeResult written() {
            return new UpgradeResult(URI.create("eddi://ai.labs.agent/agentstore/agents/" + TARGET_AGENT_ID + "?version=5"), true, 1, 0,
                    0, List.of());
        }
    }

    // ==================== preview ====================

    @Nested
    @DisplayName("preview: what happens to the running conversations")
    class Preview {

        private IPromotionImpactReader reader;

        @BeforeEach
        void giveTheServiceAReader() {
            reader = mock(IPromotionImpactReader.class);
            importService.usePromotionImpactReader(reader);
            stubEmptyZip();
        }

        @Test
        @DisplayName("an upgrade preview carries the impact of the version it would write")
        void upgradePreviewCarriesTheImpact() {
            var impact = impact();
            when(reader.previewNextVersion(TARGET_AGENT_ID)).thenReturn(impact);
            givenPreview(DiffAction.UPDATE);

            ImportPreview preview = importService.previewImport(new ByteArrayInputStream(new byte[0]), TARGET_AGENT_ID);

            assertSame(impact, preview.conversationImpact());
        }

        @Test
        @DisplayName("a preview that would write nothing has no impact to report")
        void nothingWrittenNothingReported() {
            givenPreview(DiffAction.SKIP);

            ImportPreview preview = importService.previewImport(new ByteArrayInputStream(new byte[0]), TARGET_AGENT_ID);

            assertNull(preview.conversationImpact());
            verify(reader, never()).previewNextVersion(anyString());
        }

        @Test
        @DisplayName("a preview of a new agent has no impact: nothing runs on it yet")
        void newAgentHasNoImpact() {
            when(structuralMatcher.buildPreview(any(), any(), anyBoolean())).thenReturn(
                    new ImportPreview("src", "Source", null, null, List.of(row(DiffAction.CREATE))));

            ImportPreview preview = importService.previewImport(new ByteArrayInputStream(new byte[0]), TARGET_AGENT_ID);

            assertNull(preview.conversationImpact());
            verify(reader, never()).previewNextVersion(anyString());
        }

        @Test
        @DisplayName("a caller who may not see the counts still gets the preview, without them")
        void withheldCountsDoNotFailThePreview() {
            when(reader.previewNextVersion(TARGET_AGENT_ID)).thenReturn(null);
            givenPreview(DiffAction.UPDATE);

            ImportPreview preview = importService.previewImport(new ByteArrayInputStream(new byte[0]), TARGET_AGENT_ID);

            assertNull(preview.conversationImpact());
            assertEquals(1, preview.resources().size());
        }

        @Test
        @DisplayName("a change to snippets, schedules or connections alone writes no agent version and reports no impact")
        void standaloneChangesReportNoImpact() {
            when(structuralMatcher.buildPreview(any(), eq(TARGET_AGENT_ID), anyBoolean())).thenReturn(new ImportPreview("src", "Source",
                    TARGET_AGENT_ID, "Target", List.of(row(DiffAction.SKIP), standalone("snippet", DiffAction.UPDATE),
                            standalone("schedule", DiffAction.CREATE), standalone("connection", DiffAction.CREATE))));

            ImportPreview preview = importService.previewImport(new ByteArrayInputStream(new byte[0]), TARGET_AGENT_ID);

            assertNull(preview.conversationImpact());
            verify(reader, never()).previewNextVersion(anyString());
        }

        @Test
        @DisplayName("a workflow-extension change still reports the impact: it repoints a workflow and so writes the agent")
        void extensionChangeStillReportsImpact() {
            when(structuralMatcher.buildPreview(any(), eq(TARGET_AGENT_ID), anyBoolean())).thenReturn(new ImportPreview("src", "Source",
                    TARGET_AGENT_ID, "Target", List.of(row(DiffAction.SKIP), standalone("langchain", DiffAction.UPDATE))));
            when(reader.previewNextVersion(TARGET_AGENT_ID)).thenReturn(impact());

            ImportPreview preview = importService.previewImport(new ByteArrayInputStream(new byte[0]), TARGET_AGENT_ID);

            assertNotNull(preview.conversationImpact());
        }

        private ResourceDiff standalone(String type, DiffAction action) {
            return new ResourceDiff("src-" + type, type, type, action, null, null, "type", null, null, -1);
        }

        private void givenPreview(DiffAction action) {
            when(structuralMatcher.buildPreview(any(), eq(TARGET_AGENT_ID), anyBoolean())).thenReturn(
                    new ImportPreview("src", "Source", TARGET_AGENT_ID, "Target", List.of(row(action))));
        }

        private ResourceDiff row(DiffAction action) {
            return new ResourceDiff("src", "agent", "Source", action, TARGET_AGENT_ID, 4, "position", null, null, -1);
        }

        private PromotionImpact impact() {
            return new PromotionImpact(4, 2, 2, 3, false, 5, 5,
                    List.of(new PromotionImpact.GenerationCount(2, 5, true)));
        }
    }

    // ==================== Helpers ====================

    private IAgentStore agentStore = mock(IAgentStore.class);

    /**
     * An archive holding one agent. With {@code failingSchedule} it also holds a
     * schedule the schedule API refuses, which fails the import after the agent was
     * written, so the rollback has the agent to put back.
     */
    private void stubArchive(boolean failingSchedule) throws Exception {
        doAnswer(inv -> {
            File dir = inv.getArgument(1);
            assertTrue(dir.mkdirs() || dir.isDirectory());
            writeAgent(dir);
            if (failingSchedule) {
                writeSchedule(dir);
            }
            return null;
        }).when(zipArchive).unzip(any(InputStream.class), any(File.class));
        doReturn(Response.status(400).entity("invalid cron").build()).when(restScheduleStore).createSchedule(any());
    }

    /** The archived agent matches an agent this deployment has, at version 4. */
    private void stubExistingAgent() throws Exception {
        var existingDescriptor = new DocumentDescriptor();
        existingDescriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + TARGET_AGENT_ID + "?version=4"));
        when(documentDescriptorStore.findByOriginId(AGENT_ORIGIN_ID)).thenReturn(List.of(existingDescriptor));
        when(documentDescriptorStore.getCurrentResourceId(TARGET_AGENT_ID)).thenReturn(resourceId(TARGET_AGENT_ID, 4));
        when(documentDescriptorStore.readDescriptor(TARGET_AGENT_ID, 4)).thenReturn(new DocumentDescriptor());
        // A merge snapshots what it overwrites and refuses a target it cannot read.
        when(agentStore.read(anyString(), anyInt())).thenReturn(new AgentConfiguration());
    }

    private void stubEmptyZip() {
        try {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeAgent(File dir) throws IOException {
        Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
    }

    private static void writeSchedule(File dir) throws IOException {
        File schedules = new File(dir, "schedules");
        assertTrue(schedules.mkdirs() || schedules.isDirectory());
        Files.writeString(new File(schedules, "sched1.schedule.json").toPath(),
                "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"other\"}");
    }

    @SuppressWarnings("unchecked")
    private MockedStatic<CDI> stubCdi() {
        var cdiMock = mockStatic(CDI.class);
        var cdi = mock(CDI.class);
        cdiMock.when(CDI::current).thenReturn(cdi);
        select(cdi, IAgentStore.class, agentStore);
        select(cdi, IRestScheduleStore.class, restScheduleStore);
        select(cdi, IRestAgentStore.class, restAgentStore);
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
