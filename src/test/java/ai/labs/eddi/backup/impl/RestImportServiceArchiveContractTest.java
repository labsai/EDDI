/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IRestScheduleStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.hitl.HitlSchedules;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
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
import java.util.Map;
import java.util.stream.Collectors;

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
    private IRestScheduleStore restScheduleStore;
    private SpaceContext spaceContext;
    private RestImportService importService;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private int createdSchedules;

    @BeforeEach
    void setUp() throws Exception {
        zipArchive = mock(IZipArchive.class);
        jsonSerialization = mock(IJsonSerialization.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        scheduleStore = mock(IScheduleStore.class);
        restScheduleStore = mock(IRestScheduleStore.class);
        // Every schedule write goes through the REST bean, which is where the
        // platform's schedule guards live (run-as-another-user, HITL timeout,
        // agent USE gate, cron validation, nextFire computation).
        doAnswer(inv -> {
            ScheduleConfiguration created = inv.getArgument(0);
            if (created != null) {
                created.setId("newSched" + (++createdSchedules));
            }
            return Response.status(201).entity(created).build();
        }).when(restScheduleStore).createSchedule(any());
        doReturn(Response.ok().build()).when(restScheduleStore).updateSchedule(anyString(), any());
        spaceContext = mock(SpaceContext.class);
        var templateSyntaxMigrator = mock(TemplateSyntaxMigrator.class);
        when(templateSyntaxMigrator.migrate(anyString())).thenAnswer(inv -> inv.getArgument(0));

        importService = new RestImportService(
                zipArchive, jsonSerialization,
                mock(IMigrationManager.class), documentDescriptorStore,
                templateSyntaxMigrator, mock(StructuralMatcher.class),
                mock(UpgradeExecutor.class), scheduleStore, mock(BackupMetrics.class), mock(ResourceAccessGuard.class),
                spaceContext);

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
        @DisplayName("a v5 workflow file keyed by packageExtensions keeps its steps")
        void v5PackageExtensionsAreMapped() throws Exception {
            String workflowId = "5af59eca9bcb0f31b4b3b938";
            String behaviorId = "5af59e929bcb0f31b4b3b935";

            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".bot.json").toPath(),
                        "{\"packages\":[\"eddi://ai.labs.package/packagestore/packages/"
                                + workflowId + "?version=1\"]}");
                File versionDir = new File(dir, workflowId + "/1");
                assertTrue(versionDir.mkdirs());
                // A genuine 5.x export: the workflow file is <id>.package.json and its
                // step list is keyed "packageExtensions". Unmapped, Jackson silently
                // produced an EMPTY step list (FAIL_ON_UNKNOWN_PROPERTIES is off) and
                // the import answered 201 for an agent that answers nothing.
                Files.writeString(new File(versionDir, workflowId + ".package.json").toPath(),
                        "{\"packageExtensions\":[{\"type\":\"eddi://ai.labs.behavior\",\"extensions\":{},"
                                + "\"config\":{\"uri\":\"eddi://ai.labs.behavior/behaviorstore/behaviorsets/"
                                + behaviorId + "?version=1\"}}]}");
                Files.writeString(new File(versionDir, behaviorId + ".behavior.json").toPath(),
                        "{\"behaviorGroups\":[]}");
            });

            when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class)))
                    .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), WorkflowConfiguration.class));
            when(jsonSerialization.deserialize(anyString(), eq(RuleSetConfiguration.class)))
                    .thenReturn(new RuleSetConfiguration());

            var agentStore = stubAgentCreation();
            var workflowStore = mock(IWorkflowStore.class);
            when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));
            var ruleSetStore = mock(IRuleSetStore.class);
            when(ruleSetStore.create(any())).thenReturn(resourceId("1111111122223333444455cc", 1));

            var storedWorkflow = ArgumentCaptor.forClass(WorkflowConfiguration.class);
            try (var cdi = stubCdi(IAgentStore.class, agentStore, IWorkflowStore.class, workflowStore,
                    IRuleSetStore.class, ruleSetStore)) {
                Response response = importService.importAgent(
                        new ByteArrayInputStream(new byte[0]), "create", null, null, null);

                assertEquals(201, response.getStatus());
            }

            verify(workflowStore).create(storedWorkflow.capture());
            assertFalse(storedWorkflow.getValue().getWorkflowSteps().isEmpty(),
                    "a v5 workflow must not be imported as an empty pipeline");
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
                                 "agentVersion":7,"tenantId":"other-tenant",
                                 "persistentConversationId":"conv-on-the-source",
                                 "nextFire":"2020-01-01T03:00:00Z",
                                 "cronExpression":"0 3 * * *","fireStatus":"CLAIMED",
                                 "claimedBy":"other-instance","failCount":3}""");
            });

            var agentStore = stubAgentCreation();
            try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestScheduleStore.class, restScheduleStore)) {
                importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
            }

            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            // The guarded REST surface, never IScheduleStore directly: the raw store
            // applies none of the checks that stop an archive minting a schedule which
            // runs as another user or forges a HITL approval timeout.
            verify(restScheduleStore).createSchedule(captor.capture());
            verify(scheduleStore, never()).createSchedule(any());
            ScheduleConfiguration imported = captor.getValue();

            assertEquals(NEW_AGENT_ID, imported.getAgentId(), "the schedule must fire the agent just created");
            assertEquals("nightly", imported.getName());
            assertEquals("0 3 * * *", imported.getCronExpression());
            // Fire bookkeeping is reset: an imported schedule must not inherit another
            // deployment's in-flight lease or retry counter.
            assertNull(imported.getClaimedBy());
            assertEquals(ScheduleConfiguration.FireStatus.PENDING, imported.getFireStatus());
            assertEquals(0, imported.getFailCount());
            // ... and neither its due time, its pinned agent version, nor the tenant and
            // conversation of the deployment it came from. A stale nextFire fires the
            // moment it is imported; a pinned agentVersion the new agent does not have
            // dead-letters every fire.
            assertNull(imported.getNextFire(), "nextFire must be recomputed, not carried over");
            assertEquals(0, imported.getAgentVersion(), "the imported schedule must follow the latest version");
            assertNull(imported.getTenantId());
            assertNull(imported.getPersistentConversationId());
        }

        @Test
        @DisplayName("a schedule the operator unticked in the preview is not imported")
        void deselectedScheduleIsSkipped() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                File schedules = new File(dir, "schedules");
                assertTrue(schedules.mkdirs());
                Files.writeString(new File(schedules, "keep1.schedule.json").toPath(),
                        "{\"id\":\"keepSched\",\"name\":\"nightly\",\"agentId\":\"someOtherAgent\"}");
                Files.writeString(new File(schedules, "drop1.schedule.json").toPath(),
                        "{\"id\":\"dropSched\",\"name\":\"hourly\",\"agentId\":\"someOtherAgent\"}");
            });

            var agentStore = stubAgentCreation();
            try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestScheduleStore.class, restScheduleStore)) {
                // The preview renders every schedule as a deselectable row; ignoring the
                // selection here created schedules the operator had explicitly unticked.
                importService.importAgent(new ByteArrayInputStream(new byte[0]), "create",
                        AGENT_ORIGIN_ID + ",keepSched", null, null);
            }

            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(restScheduleStore).createSchedule(captor.capture());
            assertEquals("nightly", captor.getValue().getName(),
                    "only the selected schedule may be created");
        }

        /**
         * {@code selectedResources} is one flat list over every row the preview emits,
         * so the documented selective-merge curls — which name extension origin ids and
         * nothing else — deselect every schedule in the archive by omission. That is a
         * legitimate reading of the parameter, but answering a bare 201 for an agent
         * whose nightly job did not come back is the exact failure importing schedules
         * at all was meant to end, and a script has no reason to read this instance's
         * log. So the count travels with the answer.
         */
        @Test
        @DisplayName("schedules the selection left out are counted on the response, not just in the log")
        void schedulesLeftOutBySelectionAreReportedOnTheResponse() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                File schedules = new File(dir, "schedules");
                assertTrue(schedules.mkdirs());
                Files.writeString(new File(schedules, "drop1.schedule.json").toPath(),
                        "{\"id\":\"dropSched1\",\"name\":\"nightly\",\"agentId\":\"someOtherAgent\"}");
                Files.writeString(new File(schedules, "drop2.schedule.json").toPath(),
                        "{\"id\":\"dropSched2\",\"name\":\"hourly\",\"agentId\":\"someOtherAgent\"}");
            });

            var agentStore = stubAgentCreation();
            Response response;
            try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestScheduleStore.class, restScheduleStore)) {
                // Scenario 3 of the docs: extension origin ids only, no schedule ids.
                response = importService.importAgent(new ByteArrayInputStream(new byte[0]), "merge",
                        "origin-beh-1,origin-http-1", null, null);
            }

            assertEquals(201, response.getStatus());
            verify(restScheduleStore, never()).createSchedule(any());
            assertEquals("2", response.getHeaderString("X-Schedules-Skipped"),
                    "the caller must be told its selection dropped the archive's schedules, headers were "
                            + response.getHeaders());
        }

        @Test
        @DisplayName("a merge updates the agent's existing schedule instead of adding a second copy")
        void mergeDoesNotDuplicateSchedules() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                File schedules = new File(dir, "schedules");
                assertTrue(schedules.mkdirs());
                Files.writeString(new File(schedules, "sched1.schedule.json").toPath(),
                        "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"someOtherAgent\","
                                + "\"cronExpression\":\"0 4 * * *\"}");
            });

            // strategy=merge exists for re-importing the same agent repeatedly. The
            // agent is matched and updated in place, so creating its schedules again
            // added a second, third, ... copy of every one of them: a nightly
            // consolidation firing N times per night after N promotions.
            var existingDescriptor = new DocumentDescriptor();
            existingDescriptor.setResource(URI.create(
                    "eddi://ai.labs.agent/agentstore/agents/" + NEW_AGENT_ID + "?version=4"));
            when(documentDescriptorStore.findByOriginId(AGENT_ORIGIN_ID)).thenReturn(List.of(existingDescriptor));

            var existingSchedule = new ScheduleConfiguration();
            existingSchedule.setId("alreadyHere");
            existingSchedule.setName("nightly");
            when(scheduleStore.readSchedulesByAgentId(NEW_AGENT_ID)).thenReturn(List.of(existingSchedule));
            when(scheduleStore.readSchedule("alreadyHere")).thenReturn(existingSchedule);

            var agentStore = stubAgentCreation();
            var restAgentStore = mock(IRestAgentStore.class);
            when(restAgentStore.updateAgent(anyString(), anyInt(), any())).thenReturn(Response.ok().build());
            try (var cdi = stubCdi(IAgentStore.class, agentStore,
                    IRestScheduleStore.class, restScheduleStore,
                    IRestAgentStore.class, restAgentStore)) {
                importService.importAgent(new ByteArrayInputStream(new byte[0]), "merge", null, null, null);
            }

            verify(restScheduleStore).updateSchedule(eq("alreadyHere"), any());
            verify(restScheduleStore, never()).createSchedule(any());
        }

        /**
         * Nothing makes a schedule name unique per agent — the schedule API validates
         * the body, never the name — so an archive can legitimately hold two called
         * "nightly". Matching them by name against a map computed once put both onto
         * the same target schedule: last write wins, the other archived schedule's
         * settings gone, and the import still answering 201.
         */
        @Test
        @DisplayName("two archived schedules of the same name do not both overwrite the same target")
        void sameNamedArchivedSchedulesDoNotCollapse() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                File schedules = new File(dir, "schedules");
                assertTrue(schedules.mkdirs());
                Files.writeString(new File(schedules, "sched1.schedule.json").toPath(),
                        "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"someOtherAgent\","
                                + "\"cronExpression\":\"0 4 * * *\"}");
                Files.writeString(new File(schedules, "sched2.schedule.json").toPath(),
                        "{\"id\":\"sched2\",\"name\":\"nightly\",\"agentId\":\"someOtherAgent\","
                                + "\"cronExpression\":\"0 5 * * *\"}");
            });

            var existingDescriptor = new DocumentDescriptor();
            existingDescriptor.setResource(URI.create(
                    "eddi://ai.labs.agent/agentstore/agents/" + NEW_AGENT_ID + "?version=4"));
            when(documentDescriptorStore.findByOriginId(AGENT_ORIGIN_ID)).thenReturn(List.of(existingDescriptor));

            var existingSchedule = new ScheduleConfiguration();
            existingSchedule.setId("alreadyHere");
            existingSchedule.setName("nightly");
            when(scheduleStore.readSchedulesByAgentId(NEW_AGENT_ID)).thenReturn(List.of(existingSchedule));
            when(scheduleStore.readSchedule("alreadyHere")).thenReturn(existingSchedule);

            var agentStore = stubAgentCreation();
            var restAgentStore = mock(IRestAgentStore.class);
            when(restAgentStore.updateAgent(anyString(), anyInt(), any())).thenReturn(Response.ok().build());
            try (var cdi = stubCdi(IAgentStore.class, agentStore,
                    IRestScheduleStore.class, restScheduleStore,
                    IRestAgentStore.class, restAgentStore)) {
                importService.importAgent(new ByteArrayInputStream(new byte[0]), "merge", null, null, null);
            }

            verify(restScheduleStore, times(1)).updateSchedule(eq("alreadyHere"), any());
            // The second one has no unclaimed target left, so it is created. A
            // duplicate is visible and deletable; a silently discarded schedule is
            // neither.
            verify(restScheduleStore, times(1)).createSchedule(any());
        }

        /**
         * The mirror image: the target agent itself has two schedules of that name, so
         * "the one to overwrite" is a coin toss. Picking either would drop the other's
         * settings on a 201.
         */
        @Test
        @DisplayName("an ambiguous name on the target is created rather than overwriting an arbitrary one")
        void ambiguousExistingScheduleNameIsNotMatched() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                File schedules = new File(dir, "schedules");
                assertTrue(schedules.mkdirs());
                Files.writeString(new File(schedules, "sched1.schedule.json").toPath(),
                        "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"someOtherAgent\","
                                + "\"cronExpression\":\"0 4 * * *\"}");
            });

            var existingDescriptor = new DocumentDescriptor();
            existingDescriptor.setResource(URI.create(
                    "eddi://ai.labs.agent/agentstore/agents/" + NEW_AGENT_ID + "?version=4"));
            when(documentDescriptorStore.findByOriginId(AGENT_ORIGIN_ID)).thenReturn(List.of(existingDescriptor));

            var first = new ScheduleConfiguration();
            first.setId("alreadyHere");
            first.setName("nightly");
            var second = new ScheduleConfiguration();
            second.setId("alsoHere");
            second.setName("nightly");
            when(scheduleStore.readSchedulesByAgentId(NEW_AGENT_ID)).thenReturn(List.of(first, second));
            when(scheduleStore.readSchedule("alreadyHere")).thenReturn(first);
            when(scheduleStore.readSchedule("alsoHere")).thenReturn(second);

            var agentStore = stubAgentCreation();
            var restAgentStore = mock(IRestAgentStore.class);
            when(restAgentStore.updateAgent(anyString(), anyInt(), any())).thenReturn(Response.ok().build());
            try (var cdi = stubCdi(IAgentStore.class, agentStore,
                    IRestScheduleStore.class, restScheduleStore,
                    IRestAgentStore.class, restAgentStore)) {
                importService.importAgent(new ByteArrayInputStream(new byte[0]), "merge", null, null, null);
            }

            verify(restScheduleStore, never()).updateSchedule(anyString(), any());
            verify(restScheduleStore).createSchedule(any());
        }

        @Test
        @DisplayName("a merge that fails after overwriting a schedule puts the original back")
        void mergeScheduleUpdateIsCompensated() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".descriptor.json").toPath(),
                        "{\"name\":\"Imported\"}");
                File schedules = new File(dir, "schedules");
                assertTrue(schedules.mkdirs());
                Files.writeString(new File(schedules, "sched1.schedule.json").toPath(),
                        "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"someOtherAgent\","
                                + "\"cronExpression\":\"0 4 * * *\"}");
            });

            var existingDescriptor = new DocumentDescriptor();
            existingDescriptor.setResource(URI.create(
                    "eddi://ai.labs.agent/agentstore/agents/" + NEW_AGENT_ID + "?version=4"));
            when(documentDescriptorStore.findByOriginId(AGENT_ORIGIN_ID)).thenReturn(List.of(existingDescriptor));

            // The operator's live nightly job, which the merge is about to overwrite.
            var live = new ScheduleConfiguration();
            live.setId("alreadyHere");
            live.setName("nightly");
            live.setCronExpression("0 9 * * *");
            when(scheduleStore.readSchedulesByAgentId(NEW_AGENT_ID)).thenReturn(List.of(live));
            when(scheduleStore.readSchedule("alreadyHere")).thenReturn(live);

            var agentStore = stubAgentCreation();
            var restAgentStore = mock(IRestAgentStore.class);
            when(restAgentStore.updateAgent(anyString(), anyInt(), any())).thenReturn(Response.ok().build());
            // The descriptor bookkeeping that runs AFTER the schedules blows up. The
            // agent and workflow versions are rolled back; a create is compensated by
            // a delete — but an update overwrote something, and only a snapshot taken
            // beforehand can undo that. Without one the import failed while leaving
            // the operator's nightly job running the archive's settings.
            when(documentDescriptorStore.getCurrentResourceId(NEW_AGENT_ID))
                    .thenThrow(new IllegalStateException("descriptor store down"));

            try (var cdi = stubCdi(IAgentStore.class, agentStore,
                    IRestScheduleStore.class, restScheduleStore,
                    IRestAgentStore.class, restAgentStore)) {
                assertThrows(RuntimeException.class, () -> importService.importAgent(
                        new ByteArrayInputStream(new byte[0]), "merge", null, null, null));
            }

            verify(restScheduleStore).updateSchedule(eq("alreadyHere"), any());
            var restored = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).updateSchedule(eq("alreadyHere"), restored.capture());
            assertEquals("0 9 * * *", restored.getValue().getCronExpression(),
                    "the rollback must put the target's own schedule back, not leave the archive's");
        }

        @Test
        @DisplayName("a schedule that ran as somebody else arrives unowned rather than bound to a stranger")
        void foreignUserIdIsNotCarriedOver() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                File schedules = new File(dir, "schedules");
                assertTrue(schedules.mkdirs());
                Files.writeString(new File(schedules, "sched1.schedule.json").toPath(),
                        "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"someOtherAgent\","
                                + "\"userId\":\"victim-42\"}");
            });
            when(spaceContext.currentPrincipal()).thenReturn("importer-7");

            var agentStore = stubAgentCreation();
            try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestScheduleStore.class, restScheduleStore)) {
                importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
            }

            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(restScheduleStore).createSchedule(captor.capture());
            // userId is the identity every fire ACTS AS, and it is minted by the SOURCE
            // deployment's identity provider. Carried over it either 403s the whole
            // import for a non-admin (requireOwnUserId) or, for an admin, silently
            // hands the schedule to whoever holds that id here — for a Dream schedule,
            // their memories are the ones pruned.
            assertNull(captor.getValue().getUserId(),
                    "a foreign identity must not cross deployments with the archive");
        }

        @Test
        @DisplayName("a schedule that already ran as the importing user keeps its owner")
        void ownUserIdIsKept() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                File schedules = new File(dir, "schedules");
                assertTrue(schedules.mkdirs());
                Files.writeString(new File(schedules, "sched1.schedule.json").toPath(),
                        "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"someOtherAgent\","
                                + "\"userId\":\"importer-7\"}");
            });
            when(spaceContext.currentPrincipal()).thenReturn("importer-7");

            var agentStore = stubAgentCreation();
            try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestScheduleStore.class, restScheduleStore)) {
                importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);
            }

            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(restScheduleStore).createSchedule(captor.capture());
            // Re-importing your own agent — the common case — must not cost you the
            // owner of your own Dream schedule.
            assertEquals("importer-7", captor.getValue().getUserId());
        }

        @Test
        @DisplayName("a merge leaves the owner of the schedule it overwrites in place")
        void mergeKeepsTheOwnerOfTheOverwrittenSchedule() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                File schedules = new File(dir, "schedules");
                assertTrue(schedules.mkdirs());
                // The archive ran as somebody the target has never heard of, so the
                // prepared schedule arrives without an identity of its own.
                Files.writeString(new File(schedules, "sched1.schedule.json").toPath(),
                        "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"someOtherAgent\","
                                + "\"userId\":\"victim-42\",\"cronExpression\":\"0 4 * * *\"}");
            });
            when(spaceContext.currentPrincipal()).thenReturn("importer-7");

            var existingDescriptor = new DocumentDescriptor();
            existingDescriptor.setResource(URI.create(
                    "eddi://ai.labs.agent/agentstore/agents/" + NEW_AGENT_ID + "?version=4"));
            when(documentDescriptorStore.findByOriginId(AGENT_ORIGIN_ID)).thenReturn(List.of(existingDescriptor));

            // The target's working nightly job, owned by the operator who imported it
            // the first time.
            var live = new ScheduleConfiguration();
            live.setId("alreadyHere");
            live.setName("nightly");
            live.setUserId("importer-7");
            when(scheduleStore.readSchedulesByAgentId(NEW_AGENT_ID)).thenReturn(List.of(live));
            when(scheduleStore.readSchedule("alreadyHere")).thenReturn(live);

            var agentStore = stubAgentCreation();
            var restAgentStore = mock(IRestAgentStore.class);
            when(restAgentStore.updateAgent(anyString(), anyInt(), any())).thenReturn(Response.ok().build());
            try (var cdi = stubCdi(IAgentStore.class, agentStore,
                    IRestScheduleStore.class, restScheduleStore,
                    IRestAgentStore.class, restAgentStore)) {
                importService.importAgent(new ByteArrayInputStream(new byte[0]), "merge", null, null, null);
            }

            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(restScheduleStore).updateSchedule(eq("alreadyHere"), captor.capture());
            // The update is a full replace: a body without a userId is filed under
            // system:scheduler, which Dream consolidation refuses to run as and the
            // ownership guard exempts. Every re-promotion silently stopped the nightly
            // job and made the schedule writable by every editor.
            assertEquals("importer-7", captor.getValue().getUserId(),
                    "a merge must not strip the owner the target assigned");
        }

        @Test
        @DisplayName("a merge never matches the target's HITL approval timer by name")
        void hitlTimeoutIsNotOverwrittenByMerge() throws Exception {
            String timerName = HitlSchedules.regularTimeoutScheduleName("conv-1");
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                File schedules = new File(dir, "schedules");
                assertTrue(schedules.mkdirs());
                // A crafted archive: the HITL name, but no hitlType marker, so the REST
                // bean's body guard does not recognise it.
                Files.writeString(new File(schedules, "sched1.schedule.json").toPath(),
                        "{\"id\":\"sched1\",\"name\":\"" + timerName + "\",\"agentId\":\"someOtherAgent\","
                                + "\"cronExpression\":\"0 4 * * *\"}");
            });

            var existingDescriptor = new DocumentDescriptor();
            existingDescriptor.setResource(URI.create(
                    "eddi://ai.labs.agent/agentstore/agents/" + NEW_AGENT_ID + "?version=4"));
            when(documentDescriptorStore.findByOriginId(AGENT_ORIGIN_ID)).thenReturn(List.of(existingDescriptor));

            var liveTimer = new ScheduleConfiguration();
            liveTimer.setId("liveHitlTimer");
            liveTimer.setName(timerName);
            liveTimer.setMetadata(Map.of(HitlSchedules.METADATA_TYPE_KEY, HitlSchedules.METADATA_TYPE_TIMEOUT));
            when(scheduleStore.readSchedulesByAgentId(NEW_AGENT_ID)).thenReturn(List.of(liveTimer));
            when(scheduleStore.readSchedule("liveHitlTimer")).thenReturn(liveTimer);

            var agentStore = stubAgentCreation();
            var restAgentStore = mock(IRestAgentStore.class);
            when(restAgentStore.updateAgent(anyString(), anyInt(), any())).thenReturn(Response.ok().build());
            try (var cdi = stubCdi(IAgentStore.class, agentStore,
                    IRestScheduleStore.class, restScheduleStore,
                    IRestAgentStore.class, restAgentStore)) {
                importService.importAgent(new ByteArrayInputStream(new byte[0]), "merge", null, null, null);
            }

            // requireAdminForHitl lets an admin update a stored HITL timer, so a name
            // match here would PUT over a live safety timer: the marker and the
            // deadline gone, the pending approval's ABORT/AUTO_REJECT policy disarmed.
            // The export side already refuses to archive these; the import must refuse
            // to match them.
            verify(restScheduleStore, never()).updateSchedule(eq("liveHitlTimer"), any());
            verify(restScheduleStore).createSchedule(any());
        }

        @Test
        @DisplayName("a schedule the guarded surface refuses fails the import with its own status")
        void refusedScheduleFailsTheImport() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                File schedules = new File(dir, "schedules");
                assertTrue(schedules.mkdirs());
                Files.writeString(new File(schedules, "hitl1.schedule.json").toPath(),
                        "{\"id\":\"hitl1\",\"name\":\"forged\",\"agentId\":\"someOtherAgent\","
                                + "\"userId\":\"victim-42\",\"metadata\":{\"hitlType\":\"hitl_timeout\"}}");
            });

            // The REST bean answers 400 for a forged HITL timeout and 403 for a
            // schedule that would run as somebody else. Either way the import must fail
            // with that status rather than swallowing it or reporting a 500.
            doReturn(Response.status(400).entity("HITL timeout schedules are minted internally only").build())
                    .when(restScheduleStore).createSchedule(any());

            var agentStore = stubAgentCreation();
            try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestScheduleStore.class, restScheduleStore)) {
                var ex = assertThrows(WebApplicationException.class, () -> importService.importAgent(
                        new ByteArrayInputStream(new byte[0]), "create", null, null, null));

                assertEquals(400, ex.getResponse().getStatus());
                assertTrue(ex.getMessage().contains("hitl1.schedule.json"), ex.getMessage());
            }
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

            doAnswer(inv -> {
                ScheduleConfiguration created = inv.getArgument(0);
                created.setId("newSched1");
                return Response.status(201).entity(created).build();
            }).doThrow(new IllegalStateException("schedule store down"))
                    .when(restScheduleStore).createSchedule(any());

            var agentStore = stubAgentCreation();
            try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestScheduleStore.class, restScheduleStore)) {
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

        /**
         * A merge matches an archived schedule against the target agent's own by name
         * and overwrites it in place. Reporting CREATE for that told the operator a
         * nightly job would be added while the import was about to replace theirs — the
         * one row where the difference matters, since an overwrite is what they might
         * have refused.
         * <p>
         * The second archived schedule of the same name is a CREATE, because the import
         * consumes the match rather than PUTting both onto the one target.
         */
        @Test
        @DisplayName("the preview reports UPDATE for a schedule a merge would overwrite by name")
        void previewMatchesSchedulesByNameAgainstTheTargetAgent() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
                File schedules = new File(dir, "schedules");
                assertTrue(schedules.mkdirs());
                Files.writeString(new File(schedules, "sched1.schedule.json").toPath(),
                        "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"someOtherAgent\"}");
                Files.writeString(new File(schedules, "sched2.schedule.json").toPath(),
                        "{\"id\":\"sched2\",\"name\":\"nightly\",\"agentId\":\"someOtherAgent\"}");
                Files.writeString(new File(schedules, "sched3.schedule.json").toPath(),
                        "{\"id\":\"sched3\",\"name\":\"heartbeat\",\"agentId\":\"someOtherAgent\"}");
            });

            // The agent this merge would write into, and the schedule it already runs.
            var existingDescriptor = new DocumentDescriptor();
            existingDescriptor.setResource(URI.create(
                    "eddi://ai.labs.agent/agentstore/agents/" + NEW_AGENT_ID + "?version=4"));
            when(documentDescriptorStore.findByOriginId(AGENT_ORIGIN_ID)).thenReturn(List.of(existingDescriptor));

            var existingSchedule = new ScheduleConfiguration();
            existingSchedule.setId("alreadyHere");
            existingSchedule.setName("nightly");
            when(scheduleStore.readSchedulesByAgentId(NEW_AGENT_ID)).thenReturn(List.of(existingSchedule));

            var preview = importService.previewImport(new ByteArrayInputStream(new byte[0]), null);

            var rows = preview.resources().stream()
                    .filter(d -> "schedule".equals(d.resourceType()))
                    .collect(Collectors.toMap(ResourceDiff::sourceId, d -> d));
            assertEquals(DiffAction.UPDATE, rows.get("sched1").action(),
                    "a merge overwrites the target's schedule of that name: " + rows);
            assertEquals("alreadyHere", rows.get("sched1").targetId(),
                    "the row must name the schedule the import would overwrite");
            assertEquals(DiffAction.CREATE, rows.get("sched2").action(),
                    "the match is consumed, so the second archived 'nightly' is added: " + rows);
            assertEquals(DiffAction.CREATE, rows.get("sched3").action(),
                    "a name the target does not have is added: " + rows);
        }
    }

    @Nested
    @DisplayName("an archive that references a config it does not contain")
    class MissingExtensionFile {

        private static final String WORKFLOW_ID = "bbbb11112222333344445555";
        private static final String LLM_ID = "dddd11112222333344445555";

        /**
         * A selective archive: the workflow names the LLM config, the file is not
         * there.
         */
        private void stubSelectiveArchive() throws Exception {
            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(),
                        "{\"workflows\":[\"eddi://ai.labs.workflow/workflowstore/workflows/"
                                + WORKFLOW_ID + "?version=1\"]}");
                File versionDir = new File(dir, WORKFLOW_ID + "/1");
                assertTrue(versionDir.mkdirs());
                Files.writeString(new File(versionDir, WORKFLOW_ID + ".workflow.json").toPath(),
                        "{\"workflowSteps\":[{\"type\":\"eddi://ai.labs.parser\"},"
                                + "{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":"
                                + "\"eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=1\"}}]}");
            });
        }

        @Test
        @DisplayName("a create drops the step it cannot resolve instead of failing the whole import")
        void missingExtensionFileOnCreateDropsTheStep() throws Exception {
            stubSelectiveArchive();
            when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class)))
                    .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), WorkflowConfiguration.class));
            when(jsonSerialization.serialize(any())).thenAnswer(inv -> mapper.writeValueAsString(inv.getArgument(0)));

            var agentStore = stubAgentCreation();
            var workflowStore = mock(IWorkflowStore.class);
            when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));

            Response response;
            try (var cdi = stubCdi(IAgentStore.class, agentStore, IWorkflowStore.class, workflowStore)) {
                // A create has no local copy to answer the reference from. Failing the
                // import with "The archive references ... but does not contain ..." told
                // the operator to fix an archive the product itself had written.
                response = importService.importAgent(
                        new ByteArrayInputStream(new byte[0]), "create", null, null, null);
            }

            assertEquals(201, response.getStatus());
            var stored = ArgumentCaptor.forClass(WorkflowConfiguration.class);
            verify(workflowStore).create(stored.capture());
            assertEquals(1, stored.getValue().getWorkflowSteps().size(),
                    "the unresolvable step must be dropped, kept: " + stored.getValue().getWorkflowSteps().stream()
                            .map(step -> String.valueOf(step.getType())).toList());
            assertEquals("eddi://ai.labs.parser", String.valueOf(
                    stored.getValue().getWorkflowSteps().getFirst().getType()),
                    "only the step whose config is missing may be dropped");
        }

        @Test
        @DisplayName("a merge is a 400 naming the resource when neither side has the config")
        void missingExtensionFileOnMergeWithNoLocalCopyIsBadRequest() throws Exception {
            stubSelectiveArchive();

            // Nothing in the archive, nothing on this deployment: the reference cannot
            // be answered at all, so the import says which resource and why. Saying so
            // beats a 500 whose message was literally "null", and beats storing a
            // workflow step that points at an id this instance does not have.
            when(documentDescriptorStore.findByOriginId(anyString())).thenReturn(List.of());
            when(documentDescriptorStore.getCurrentResourceId(anyString())).thenReturn(null);

            var ex = assertThrows(BadRequestException.class, () -> importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "merge", LLM_ID, null, null));

            assertTrue(ex.getMessage().contains(LLM_ID), ex.getMessage());
            assertTrue(ex.getMessage().contains("no copy of it"), ex.getMessage());
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

        /**
         * The promotion flow the merge matching exists for: export only the behaviour
         * rules from staging, merge them into a prod agent that has its own LLM config.
         * A merge PUTs the archived workflow over the target's — a full replace — so an
         * archive that arrives without the LLM step does not leave prod's step alone,
         * it deletes it, silently, with a 201.
         */
        @Test
        @DisplayName("a merge onto an existing workflow keeps the step whose config the archive omits")
        void mergeKeepsTheTargetsStepForAnOmittedConfig() throws Exception {
            String workflowId = "bbbb11112222333344445555";
            String llmId = "dddd11112222333344445555";
            String localLlmId = "9999111122223333444455aa";
            String localWorkflowId = "8888111122223333444455bb";

            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(),
                        "{\"workflows\":[\"eddi://ai.labs.workflow/workflowstore/workflows/"
                                + workflowId + "?version=1\"]}");
                File versionDir = new File(dir, workflowId + "/1");
                assertTrue(versionDir.mkdirs());
                // Exactly what a selective export writes: the file is omitted, the
                // reference stays.
                Files.writeString(new File(versionDir, workflowId + ".workflow.json").toPath(),
                        "{\"workflowSteps\":[{\"type\":\"eddi://ai.labs.parser\"},"
                                + "{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":"
                                + "\"eddi://ai.labs.llm/llmstore/llms/" + llmId + "?version=1\"}}]}");
            });

            when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class)))
                    .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), WorkflowConfiguration.class));

            // The target already runs this workflow, with its own copy of the config
            // the archive left out.
            var localWorkflow = new DocumentDescriptor();
            localWorkflow.setResource(URI.create(
                    "eddi://ai.labs.workflow/workflowstore/workflows/" + localWorkflowId + "?version=2"));
            when(documentDescriptorStore.findByOriginId(workflowId)).thenReturn(List.of(localWorkflow));
            var localLlm = new DocumentDescriptor();
            localLlm.setResource(URI.create(
                    "eddi://ai.labs.llm/llmstore/llms/" + localLlmId + "?version=3"));
            when(documentDescriptorStore.findByOriginId(llmId)).thenReturn(List.of(localLlm));

            var agentStore = stubAgentCreation();
            var restWorkflowStore = mock(IRestWorkflowStore.class);
            when(restWorkflowStore.updateWorkflow(anyString(), anyInt(), any())).thenReturn(Response.ok().build());

            try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestWorkflowStore.class, restWorkflowStore)) {
                Response response = importService.importAgent(
                        new ByteArrayInputStream(new byte[0]), "merge", workflowId, null, null);

                assertEquals(201, response.getStatus());
            }

            var updated = ArgumentCaptor.forClass(WorkflowConfiguration.class);
            verify(restWorkflowStore).updateWorkflow(eq(localWorkflowId), eq(2), updated.capture());
            var steps = updated.getValue().getWorkflowSteps();
            assertEquals(2, steps.size(), "the merge must not delete a step from the live target, kept: "
                    + steps.stream().map(step -> String.valueOf(step.getType())).toList());
            Object uri = steps.get(1).getConfig().get("uri");
            assertTrue(String.valueOf(uri).contains(localLlmId),
                    "the surviving step must point at the target's own copy, was " + uri);
        }

        /**
         * The same archive on the endpoint's <em>default</em> selection —
         * {@code selectedResources} absent, which is what the documented curl and the
         * OpenAPI default send.
         * <p>
         * "No selection" means "everything", so the omitted config counted as selected
         * and the absent file was read as a broken archive: the whole promotion flow
         * answered 400, with a message telling a merge caller to merge.
         */
        @Test
        @DisplayName("a merge with no selectedResources keeps the target's copy instead of answering 400")
        void mergeWithDefaultSelectionKeepsTheTargetsCopy() throws Exception {
            String workflowId = "bbbb11112222333344445555";
            String llmId = "dddd11112222333344445555";
            String localLlmId = "9999111122223333444455aa";
            String localWorkflowId = "8888111122223333444455bb";

            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(),
                        "{\"workflows\":[\"eddi://ai.labs.workflow/workflowstore/workflows/"
                                + workflowId + "?version=1\"]}");
                File versionDir = new File(dir, workflowId + "/1");
                assertTrue(versionDir.mkdirs());
                // Exactly what a selective export writes: the file is omitted, the
                // reference stays.
                Files.writeString(new File(versionDir, workflowId + ".workflow.json").toPath(),
                        "{\"workflowSteps\":[{\"type\":\"eddi://ai.labs.parser\"},"
                                + "{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":"
                                + "\"eddi://ai.labs.llm/llmstore/llms/" + llmId + "?version=1\"}}]}");
            });

            when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class)))
                    .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), WorkflowConfiguration.class));

            var localWorkflow = new DocumentDescriptor();
            localWorkflow.setResource(URI.create(
                    "eddi://ai.labs.workflow/workflowstore/workflows/" + localWorkflowId + "?version=2"));
            when(documentDescriptorStore.findByOriginId(workflowId)).thenReturn(List.of(localWorkflow));
            var localLlm = new DocumentDescriptor();
            localLlm.setResource(URI.create(
                    "eddi://ai.labs.llm/llmstore/llms/" + localLlmId + "?version=3"));
            when(documentDescriptorStore.findByOriginId(llmId)).thenReturn(List.of(localLlm));

            var agentStore = stubAgentCreation();
            var restWorkflowStore = mock(IRestWorkflowStore.class);
            when(restWorkflowStore.updateWorkflow(anyString(), anyInt(), any())).thenReturn(Response.ok().build());

            try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestWorkflowStore.class, restWorkflowStore)) {
                Response response = importService.importAgent(
                        new ByteArrayInputStream(new byte[0]), "merge", null, null, null);

                assertEquals(201, response.getStatus(), "the default merge of EDDI's own selective archive"
                        + " must import, not 400");
            }

            var updated = ArgumentCaptor.forClass(WorkflowConfiguration.class);
            verify(restWorkflowStore).updateWorkflow(eq(localWorkflowId), eq(2), updated.capture());
            var steps = updated.getValue().getWorkflowSteps();
            assertEquals(2, steps.size(), "the merge must not delete a step from the live target, kept: "
                    + steps.stream().map(step -> String.valueOf(step.getType())).toList());
            Object uri = steps.get(1).getConfig().get("uri");
            assertTrue(String.valueOf(uri).contains(localLlmId),
                    "the surviving step must point at the target's own copy, was " + uri);
        }

        /**
         * The same archive as the Manager's wizard actually posts it. Its merge step
         * ticks every preview row — {@code setSelected(new Set(data.resources.map(r =>
         * r.sourceId)))} — and posts the lot as {@code selectedResources}, with no
         * filter on the row's action, so the SKIP row for the config the archive omits
         * is named too.
         * <p>
         * Treating a named id as proof of a broken archive therefore answered 400 to
         * the product's own default selection, on the very promotion flow the SKIP row
         * was introduced to describe. Whether the caller names the id decides nothing
         * here: a merge answers an omitted config from the target's copy, and the one
         * case that is genuinely broken — nothing on either side — is caught with a
         * message naming the resource.
         */
        @Test
        @DisplayName("a merge that names every preview row, SKIP included, keeps the target's copy")
        void mergeWithEveryPreviewRowTickedKeepsTheTargetsCopy() throws Exception {
            String workflowId = "bbbb11112222333344445555";
            String llmId = "dddd11112222333344445555";
            String localLlmId = "9999111122223333444455aa";
            String localWorkflowId = "8888111122223333444455bb";

            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(),
                        "{\"workflows\":[\"eddi://ai.labs.workflow/workflowstore/workflows/"
                                + workflowId + "?version=1\"]}");
                File versionDir = new File(dir, workflowId + "/1");
                assertTrue(versionDir.mkdirs());
                Files.writeString(new File(versionDir, workflowId + ".workflow.json").toPath(),
                        "{\"workflowSteps\":[{\"type\":\"eddi://ai.labs.parser\"},"
                                + "{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":"
                                + "\"eddi://ai.labs.llm/llmstore/llms/" + llmId + "?version=1\"}}]}");
            });

            when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class)))
                    .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), WorkflowConfiguration.class));

            var localWorkflow = new DocumentDescriptor();
            localWorkflow.setResource(URI.create(
                    "eddi://ai.labs.workflow/workflowstore/workflows/" + localWorkflowId + "?version=2"));
            when(documentDescriptorStore.findByOriginId(workflowId)).thenReturn(List.of(localWorkflow));
            var localLlm = new DocumentDescriptor();
            localLlm.setResource(URI.create(
                    "eddi://ai.labs.llm/llmstore/llms/" + localLlmId + "?version=3"));
            when(documentDescriptorStore.findByOriginId(llmId)).thenReturn(List.of(localLlm));

            var agentStore = stubAgentCreation();
            var restWorkflowStore = mock(IRestWorkflowStore.class);
            when(restWorkflowStore.updateWorkflow(anyString(), anyInt(), any())).thenReturn(Response.ok().build());

            // Every sourceId the preview handed the wizard: the agent row, the
            // workflow row, and the SKIP row for the config the archive omits.
            String everyTickedRow = AGENT_ORIGIN_ID + "," + workflowId + "," + llmId;

            try (var cdi = stubCdi(IAgentStore.class, agentStore, IRestWorkflowStore.class, restWorkflowStore)) {
                Response response = importService.importAgent(
                        new ByteArrayInputStream(new byte[0]), "merge", everyTickedRow, null, null);

                assertEquals(201, response.getStatus(), "the Manager's own default selection must import,"
                        + " not 400 on the row it presents as harmless");
            }

            var updated = ArgumentCaptor.forClass(WorkflowConfiguration.class);
            verify(restWorkflowStore).updateWorkflow(eq(localWorkflowId), eq(2), updated.capture());
            var steps = updated.getValue().getWorkflowSteps();
            assertEquals(2, steps.size(), "the merge must not delete a step from the live target, kept: "
                    + steps.stream().map(step -> String.valueOf(step.getType())).toList());
            Object uri = steps.get(1).getConfig().get("uri");
            assertTrue(String.valueOf(uri).contains(localLlmId),
                    "the surviving step must point at the target's own copy, was " + uri);
        }

        /**
         * The preview the Manager ticks its rows from. A config the archive does not
         * carry is not going to be updated by the import — it is answered from the
         * target's copy — so an UPDATE row both described a write that never happens
         * and, once turned back into {@code selectedResources}, named an id the archive
         * lacks, which the import then refuses.
         */
        @Test
        @DisplayName("the merge preview marks a referenced-but-absent config SKIP, not UPDATE")
        void previewMarksAnOmittedConfigAsSkip() throws Exception {
            String workflowId = "bbbb11112222333344445555";
            String llmId = "dddd11112222333344445555";
            String localLlmId = "9999111122223333444455aa";

            stubUnzip(dir -> {
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(),
                        "{\"workflows\":[\"eddi://ai.labs.workflow/workflowstore/workflows/"
                                + workflowId + "?version=1\"]}");
                File versionDir = new File(dir, workflowId + "/1");
                assertTrue(versionDir.mkdirs());
                Files.writeString(new File(versionDir, workflowId + ".workflow.json").toPath(),
                        "{\"workflowSteps\":[{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":"
                                + "\"eddi://ai.labs.llm/llmstore/llms/" + llmId + "?version=1\"}}]}");
            });

            var localLlm = new DocumentDescriptor();
            localLlm.setResource(URI.create(
                    "eddi://ai.labs.llm/llmstore/llms/" + localLlmId + "?version=3"));
            when(documentDescriptorStore.findByOriginId(llmId)).thenReturn(List.of(localLlm));

            var preview = importService.previewImport(new ByteArrayInputStream(new byte[0]), null);

            var llmRow = preview.resources().stream()
                    .filter(diff -> llmId.equals(diff.sourceId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no row for the referenced config: " + preview.resources()));
            assertEquals(DiffAction.SKIP, llmRow.action(),
                    "a config the archive does not carry is left alone by the import");
            assertEquals(localLlmId, llmRow.targetId(),
                    "the row must still name the local copy the import will keep");
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

    private <A, B, C> AutoCloseable stubCdi(Class<A> firstClass, A first, Class<B> secondClass, B second,
                                            Class<C> thirdClass, C third) {
        var cdiMock = mockStatic(CDI.class);
        var cdi = mock(CDI.class);
        cdiMock.when(CDI::current).thenReturn(cdi);
        select(cdi, firstClass, first);
        select(cdi, secondClass, second);
        select(cdi, thirdClass, third);
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
