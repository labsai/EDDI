/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * What an import archive is allowed to contain, and what happens to the parts
 * of it the importer used to ignore.
 * <ul>
 * <li>An EDDI 5.x archive names its agent file {@code <id>.bot.json}; the
 * importer accepted the v5 {@code .package.json} workflow file and normalized
 * v5 URIs but looked only for {@code .agent.json}, so a 5.x import created
 * nothing and answered 200 OK.</li>
 * <li>Export writes a {@code schedules/} directory; nothing read it back, so a
 * restore-from-backup came up with every cron and heartbeat silently gone.</li>
 * <li>A config the archive references but does not contain produced a bare
 * NullPointerException surfaced as a 500 whose message was literally
 * "null".</li>
 * </ul>
 */
@DisplayName("RestImportService — archive contract")
class RestImportServiceArchiveContractTest {

    private static final String AGENT_ORIGIN_ID = "aabb11112222333344445555";
    private static final String NEW_AGENT_ID = "ccdd11112222333344445555";
    private static final String NEW_WORKFLOW_ID = "7777111122223333444455bb";

    private IZipArchive zipArchive;
    private IJsonSerialization jsonSerialization;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IScheduleStore scheduleStore;
    private RestImportService importService;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        zipArchive = mock(IZipArchive.class);
        jsonSerialization = mock(IJsonSerialization.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        scheduleStore = mock(IScheduleStore.class);
        var templateSyntaxMigrator = mock(TemplateSyntaxMigrator.class);
        when(templateSyntaxMigrator.migrate(anyString())).thenAnswer(inv -> inv.getArgument(0));

        importService = new RestImportService(
                zipArchive, jsonSerialization,
                mock(IMigrationManager.class), documentDescriptorStore,
                templateSyntaxMigrator, mock(StructuralMatcher.class),
                mock(UpgradeExecutor.class), scheduleStore, mock(BackupMetrics.class), mock(ResourceAccessGuard.class));

        when(jsonSerialization.deserialize(anyString(), eq(AgentConfiguration.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), AgentConfiguration.class));
        when(jsonSerialization.deserialize(anyString(), eq(DocumentDescriptor.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), DocumentDescriptor.class));
        when(jsonSerialization.deserialize(anyString(), eq(ScheduleConfiguration.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), ScheduleConfiguration.class));
    }

    @Nested
    @DisplayName("EDDI 5.x archives")
    class LegacyArchives {

        @Test
        @DisplayName("an agent file named <id>.bot.json is imported, not silently ignored")
        void v5AgentFileIsImported() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".bot.json").toPath(), "{\"workflows\":[]}");
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".descriptor.json").toPath(),
                        "{\"name\":\"Legacy Agent\"}");
            });

            var agentStore = stubAgentCreation();
            try (var cdi = stubCdi(IAgentStore.class, agentStore)) {
                Response response = importService.importAgent(
                        new ByteArrayInputStream(new byte[0]), "create", null, null, null);

                // The 200-with-empty-resourceUri answer is what let an operator
                // upgrading from 5.x believe their agent had been imported.
                assertEquals(201, response.getStatus());
                assertTrue(response.getHeaderString("Location").contains(NEW_AGENT_ID));
                verify(agentStore).create(any());
            }
        }

        @Test
        @DisplayName("a preview of a v5 archive names the agent it found")
        void v5PreviewFindsAgent() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".bot.json").toPath(), "{\"workflows\":[]}");
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".descriptor.json").toPath(),
                        "{\"name\":\"Legacy Agent\"}");
            });

            var preview = importService.previewImport(new ByteArrayInputStream(new byte[0]), null);

            assertEquals(AGENT_ORIGIN_ID, preview.sourceAgentId());
            assertFalse(preview.resources().isEmpty());
        }
    }

    @Nested
    @DisplayName("schedules")
    class Schedules {

        @Test
        @DisplayName("schedules in the archive are recreated and repointed at the imported agent")
        void schedulesAreImported() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                File schedules = new File(dir, "schedules");
                assertTrue(schedules.mkdirs());
                Files.writeString(new File(schedules, "sched1.schedule.json").toPath(),
                        """
                                {"id":"sched1","name":"nightly","agentId":"someOtherAgent",
                                 "cronExpression":"0 3 * * *","fireStatus":"CLAIMED",
                                 "claimedBy":"other-instance","failCount":3}""");
            });

            when(scheduleStore.createSchedule(any())).thenReturn("newSched1");

            var agentStore = stubAgentCreation();
            try (var cdi = stubCdi(IAgentStore.class, agentStore)) {
                importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
            }

            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createSchedule(captor.capture());
            ScheduleConfiguration imported = captor.getValue();

            assertEquals(NEW_AGENT_ID, imported.getAgentId(), "the schedule must fire the agent just created");
            assertEquals("nightly", imported.getName());
            assertEquals("0 3 * * *", imported.getCronExpression());
            // Fire bookkeeping is reset: an imported schedule must not inherit another
            // deployment's in-flight lease or retry counter.
            assertNull(imported.getId());
            assertNull(imported.getClaimedBy());
            assertEquals(ScheduleConfiguration.FireStatus.PENDING, imported.getFireStatus());
            assertEquals(0, imported.getFailCount());
        }

        @Test
        @DisplayName("a schedule that cannot be created fails the import and rolls the others back")
        void scheduleFailureRollsBack() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                File schedules = new File(dir, "schedules");
                assertTrue(schedules.mkdirs());
                Files.writeString(new File(schedules, "aaa1.schedule.json").toPath(),
                        "{\"id\":\"aaa1\",\"name\":\"nightly\",\"agentId\":\"someOtherAgent\"}");
                Files.writeString(new File(schedules, "bbb2.schedule.json").toPath(),
                        "{\"id\":\"bbb2\",\"name\":\"hourly\",\"agentId\":\"someOtherAgent\"}");
            });

            when(scheduleStore.createSchedule(any()))
                    .thenReturn("newSched1")
                    .thenThrow(new IllegalStateException("schedule store down"));

            var agentStore = stubAgentCreation();
            try (var cdi = stubCdi(IAgentStore.class, agentStore)) {
                // Restoring an agent with only half its triggers is the failure mode
                // importing schedules exists to remove, so it is not tolerated.
                assertThrows(RuntimeException.class, () -> importService.importAgent(
                        new ByteArrayInputStream(new byte[0]), "create", null, null, null));
            }

            verify(scheduleStore).deleteSchedule("newSched1");
        }

        @Test
        @DisplayName("the preview lists the schedules a restore would bring back")
        void schedulesAppearInPreview() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                File schedules = new File(dir, "schedules");
                assertTrue(schedules.mkdirs());
                Files.writeString(new File(schedules, "sched1.schedule.json").toPath(),
                        "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"someOtherAgent\"}");
            });

            var preview = importService.previewImport(new ByteArrayInputStream(new byte[0]), null);

            assertTrue(preview.resources().stream().anyMatch(d -> "schedule".equals(d.resourceType())),
                    "schedules must be visible in the preview: " + preview.resources());
        }
    }

    @Nested
    @DisplayName("an archive that references a config it does not contain")
    class MissingExtensionFile {

        @Test
        @DisplayName("is a 400 naming the missing file, not a 500 whose message is 'null'")
        void missingExtensionFileIsBadRequest() throws Exception {
            String workflowId = "bbbb11112222333344445555";
            String llmId = "dddd11112222333344445555";

            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(),
                        "{\"workflows\":[\"eddi://ai.labs.workflow/workflowstore/workflows/"
                                + workflowId + "?version=1\"]}");
                File versionDir = new File(dir, workflowId + "/1");
                assertTrue(versionDir.mkdirs());
                // The workflow still carries the URI of a config a selective export
                // left out of the archive.
                Files.writeString(new File(versionDir, workflowId + ".workflow.json").toPath(),
                        "{\"workflowSteps\":[{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":"
                                + "\"eddi://ai.labs.llm/llmstore/llms/" + llmId + "?version=1\"}}]}");
            });

            var ex = assertThrows(BadRequestException.class, () -> importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "create", null, null, null));

            assertTrue(ex.getMessage().contains(llmId + ".langchain.json"), ex.getMessage());
        }
    }

    @Nested
    @DisplayName("how many agents an archive may hold")
    class AgentFileCount {

        @Test
        @DisplayName("two agent files are a 400, not a silent import of both")
        void twoAgentFilesAreRejected() throws Exception {
            String secondAgentId = "eeff11112222333344445555";
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                Files.writeString(new File(dir, secondAgentId + ".agent.json").toPath(), "{\"workflows\":[]}");
            });

            var agentStore = stubAgentCreation();
            try (var cdi = stubCdi(IAgentStore.class, agentStore)) {
                // The preview described only the first file it enumerated while the
                // import created every one of them, so an operator approved one agent
                // and got several, with a Location header naming whichever came last.
                var ex = assertThrows(BadRequestException.class, () -> importService.importAgent(
                        new ByteArrayInputStream(new byte[0]), "create", null, null, null));

                assertTrue(ex.getMessage().contains("2"), ex.getMessage());
                verify(agentStore, never()).create(any());
            }
        }

        @Test
        @DisplayName("a preview of a two-agent archive is rejected the same way")
        void twoAgentFilesAreRejectedInPreview() throws Exception {
            String secondAgentId = "eeff11112222333344445555";
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                Files.writeString(new File(dir, secondAgentId + ".agent.json").toPath(), "{\"workflows\":[]}");
            });

            assertThrows(BadRequestException.class, () -> importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), null));
        }
    }

    @Nested
    @DisplayName("selective merge")
    class SelectiveMerge {

        @Test
        @DisplayName("a deselected resource with no local counterpart fails the import instead of writing a dangling URI")
        void deselectedResourceWithNoLocalCopyIsRejected() throws Exception {
            String workflowId = "bbbb11112222333344445555";
            String llmId = "dddd11112222333344445555";

            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(),
                        "{\"workflows\":[\"eddi://ai.labs.workflow/workflowstore/workflows/"
                                + workflowId + "?version=1\"]}");
                File versionDir = new File(dir, workflowId + "/1");
                assertTrue(versionDir.mkdirs());
                Files.writeString(new File(versionDir, workflowId + ".workflow.json").toPath(),
                        "{\"workflowSteps\":[{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":"
                                + "\"eddi://ai.labs.llm/llmstore/llms/" + llmId + "?version=1\"}}]}");
                Files.writeString(new File(versionDir, llmId + ".langchain.json").toPath(), "{\"tasks\":[]}");
            });

            when(jsonSerialization.deserialize(anyString(), eq(LlmConfiguration.class)))
                    .thenReturn(new LlmConfiguration(List.of()));
            // This deployment has never seen the deselected resource.
            when(documentDescriptorStore.findByOriginId(anyString())).thenReturn(List.of());
            when(documentDescriptorStore.getCurrentResourceId(anyString())).thenReturn(null);

            // The operator unticks the LLM config — exactly the case the checkbox
            // exists for. Keeping the SOURCE deployment's URI stored a workflow step
            // pointing at a resource id that does not exist here, and the failure only
            // surfaced later, at deployment or the first conversation turn.
            var ex = assertThrows(BadRequestException.class, () -> importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "merge", AGENT_ORIGIN_ID, null, null));

            assertTrue(ex.getMessage().contains(llmId), ex.getMessage());
        }

        /**
         * The compensating half of the missing-file 400 above. EDDI's own selective
         * export omits a deselected config from the archive but leaves its URI in the
         * workflow JSON, and merge answered such an archive from the local deployment
         * without ever opening the file. Failing before the selection is applied made
         * the importer reject archives the exporter had just written.
         */
        @Test
        @DisplayName("a deselected resource the archive omits is answered from the local copy, not rejected")
        void deselectedResourceOmittedFromArchiveUsesTheLocalCopy() throws Exception {
            String workflowId = "bbbb11112222333344445555";
            String llmId = "dddd11112222333344445555";
            String localLlmId = "9999111122223333444455aa";

            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(),
                        "{\"workflows\":[\"eddi://ai.labs.workflow/workflowstore/workflows/"
                                + workflowId + "?version=1\"]}");
                File versionDir = new File(dir, workflowId + "/1");
                assertTrue(versionDir.mkdirs());
                // The workflow keeps the URI; the selective export left the file out.
                Files.writeString(new File(versionDir, workflowId + ".workflow.json").toPath(),
                        "{\"workflowSteps\":[{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":"
                                + "\"eddi://ai.labs.llm/llmstore/llms/" + llmId + "?version=1\"}}]}");
            });

            when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class)))
                    .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), WorkflowConfiguration.class));

            // This deployment already has the excluded LLM config.
            var localDescriptor = new DocumentDescriptor();
            localDescriptor.setResource(URI.create(
                    "eddi://ai.labs.llm/llmstore/llms/" + localLlmId + "?version=3"));
            when(documentDescriptorStore.findByOriginId(llmId)).thenReturn(List.of(localDescriptor));
            when(documentDescriptorStore.findByOriginId(workflowId)).thenReturn(List.of());

            var agentStore = stubAgentCreation();
            var workflowStore = mock(IWorkflowStore.class);
            when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));

            var storedWorkflow = ArgumentCaptor.forClass(WorkflowConfiguration.class);
            try (var cdi = stubCdi(IAgentStore.class, agentStore, IWorkflowStore.class, workflowStore)) {
                Response response = importService.importAgent(
                        new ByteArrayInputStream(new byte[0]), "merge", workflowId, null, null);

                assertEquals(201, response.getStatus());
            }

            verify(workflowStore).create(storedWorkflow.capture());
            Object uri = storedWorkflow.getValue().getWorkflowSteps().getFirst().getConfig().get("uri");
            assertTrue(String.valueOf(uri).contains(localLlmId),
                    "the stored workflow must point at this deployment's own copy, was " + uri);
        }
    }

    // ==================== Helpers ====================

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

    private <T> AutoCloseable stubCdi(Class<T> storeClass, T store) {
        var cdiMock = mockStatic(CDI.class);
        var cdi = mock(CDI.class);
        cdiMock.when(CDI::current).thenReturn(cdi);
        select(cdi, storeClass, store);
        return cdiMock;
    }

    private <A, B> AutoCloseable stubCdi(Class<A> firstClass, A first, Class<B> secondClass, B second) {
        var cdiMock = mockStatic(CDI.class);
        var cdi = mock(CDI.class);
        cdiMock.when(CDI::current).thenReturn(cdi);
        select(cdi, firstClass, first);
        select(cdi, secondClass, second);
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
