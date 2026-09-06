/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IRestScheduleStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.security.ForbiddenException;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.WebApplicationException;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an import does when the schedule surface refuses, misbehaves, or cannot
 * be read.
 * <p>
 * Restoring an agent whose nightly job is silently missing is the failure that
 * importing schedules at all exists to remove, so none of these paths may end
 * in a quiet 201. Equally, a merge that overwrites a live cron must be able to
 * put it back: the rollback that restores the agent is worthless if the
 * operator's schedule stays replaced by the archive's.
 */
@DisplayName("RestImportService — schedules that cannot be written")
class RestImportServiceScheduleFailureTest {

    private static final String AGENT_ORIGIN_ID = "aabb11112222333344445555";
    private static final String NEW_AGENT_ID = "ccdd11112222333344445555";

    private IZipArchive zipArchive;
    private IJsonSerialization jsonSerialization;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IScheduleStore scheduleStore;
    private IRestScheduleStore restScheduleStore;
    private SpaceContext spaceContext;
    private BackupMetrics metrics;
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
        spaceContext = mock(SpaceContext.class);
        metrics = mock(BackupMetrics.class);

        doAnswer(inv -> {
            ScheduleConfiguration created = inv.getArgument(0);
            if (created != null) {
                created.setId("newSched" + (++createdSchedules));
            }
            return Response.status(201).entity(created).build();
        }).when(restScheduleStore).createSchedule(any());
        doReturn(Response.ok().build()).when(restScheduleStore).updateSchedule(anyString(), any());

        var templateSyntaxMigrator = mock(TemplateSyntaxMigrator.class);
        when(templateSyntaxMigrator.migrate(anyString())).thenAnswer(inv -> inv.getArgument(0));

        importService = new RestImportService(
                zipArchive, jsonSerialization,
                mock(IMigrationManager.class), documentDescriptorStore,
                templateSyntaxMigrator, mock(StructuralMatcher.class),
                mock(UpgradeExecutor.class), scheduleStore, metrics,
                mock(ResourceAccessGuard.class), spaceContext);

        when(jsonSerialization.deserialize(anyString(), eq(AgentConfiguration.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), AgentConfiguration.class));
        when(jsonSerialization.deserialize(anyString(), eq(DocumentDescriptor.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), DocumentDescriptor.class));
        when(jsonSerialization.deserialize(anyString(), eq(ScheduleConfiguration.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), ScheduleConfiguration.class));
    }

    /**
     * A {@code schedules/} directory holding nothing the importer recognises is not
     * a failure and not a skipped-schedule count either — there was nothing to
     * import.
     */
    @Test
    @DisplayName("a schedules directory with no schedule files is imported as nothing")
    void emptySchedulesDirectoryIsNotAnError() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            File schedules = new File(dir, "schedules");
            assertTrue(schedules.mkdirs());
            Files.writeString(new File(schedules, "README.txt").toPath(), "not a schedule");
        });

        Response response = runImport("create", null);

        assertEquals(201, response.getStatus());
        assertNull(response.getHeaderString("X-Schedules-Skipped"),
                "nothing was left out, so nothing may be reported as left out");
        verify(restScheduleStore, never()).createSchedule(any());
    }

    /**
     * The schedule API's own refusal — an agent the caller may not use, a HITL
     * timer nobody may mint, an invalid cron — must reach the caller with its own
     * status. Reporting it as a 500 tells the operator the server is broken about a
     * request the platform deliberately declined.
     */
    @Test
    @DisplayName("a refusal from the schedule API keeps its status instead of becoming a 500")
    void scheduleApiRefusalKeepsItsStatus() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            writeSchedule(dir, "sched1", "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"other\"}");
        });
        doReturn(Response.status(403).entity("agent not usable by this caller").build())
                .when(restScheduleStore).createSchedule(any());

        var thrown = assertThrows(WebApplicationException.class, () -> runImport("create", null));

        assertEquals(403, thrown.getResponse().getStatus());
        assertTrue(thrown.getMessage().contains("agent not usable by this caller"),
                "the refusal's own reason must survive: " + thrown.getMessage());
        verify(metrics).importFailed();
    }

    /**
     * A schedule surface that answers nothing at all is a server fault, and must be
     * reported as one rather than counted as an import that succeeded.
     */
    @Test
    @DisplayName("a schedule surface answering nothing fails the import as a 500")
    void scheduleApiAnsweringNothingIsAServerError() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            writeSchedule(dir, "sched1", "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"other\"}");
        });
        doReturn(null).when(restScheduleStore).createSchedule(any());

        var thrown = assertThrows(WebApplicationException.class, () -> runImport("create", null));

        assertEquals(500, thrown.getResponse().getStatus());
    }

    /**
     * The platform's own authorization refusal is a 403 the caller can act on. The
     * import's catch-all would otherwise turn it into "the server is broken".
     */
    @Test
    @DisplayName("a ForbiddenException from the schedule API reaches the caller as a 403")
    void forbiddenFromScheduleApiIsNotRepackaged() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            writeSchedule(dir, "sched1", "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"other\"}");
        });
        var forbidden = new ForbiddenException("not allowed to run as that user");
        doThrow(forbidden).when(restScheduleStore).createSchedule(any());

        var thrown = assertThrows(ForbiddenException.class, () -> runImport("create", null));

        assertEquals(forbidden.getMessage(), thrown.getMessage());
        verify(metrics).importFailed();
    }

    /**
     * Failing closed here would refuse a restore because the store hiccuped.
     * Failing open re-creates a schedule the agent already has — visible and
     * deletable, which is the lesser harm — so the merge continues.
     */
    @Test
    @DisplayName("a store that cannot list the agent's schedules still lets the merge finish")
    void unlistableExistingSchedulesFallBackToCreating() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            writeSchedule(dir, "sched1", "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"other\"}");
        });
        stubExistingAgentForMerge();
        doAnswer(inv -> {
            throw new IllegalStateException("schedule listing unavailable");
        }).when(scheduleStore).readSchedulesByAgentId(NEW_AGENT_ID);

        Response response = runImport("merge", null);

        assertEquals(201, response.getStatus());
        verify(restScheduleStore).createSchedule(any());
        verify(restScheduleStore, never()).updateSchedule(anyString(), any());
    }

    /**
     * A schedule this import could not snapshot is one it must not overwrite: an
     * overwrite it cannot undo would survive the rollback that puts everything else
     * back. It is imported as a new schedule instead.
     */
    @Test
    @DisplayName("a target schedule that cannot be snapshotted is added, never overwritten")
    void unsnapshottableTargetIsCreatedInstead() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            writeSchedule(dir, "sched1", "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"other\"}");
        });
        stubExistingAgentForMerge();

        var existing = new ScheduleConfiguration();
        existing.setId("alreadyHere");
        existing.setName("nightly");
        when(scheduleStore.readSchedulesByAgentId(NEW_AGENT_ID)).thenReturn(List.of(existing));
        doAnswer(inv -> {
            throw new IllegalStateException("cannot read that schedule");
        }).when(scheduleStore).readSchedule("alreadyHere");

        Response response = runImport("merge", null);

        assertEquals(201, response.getStatus());
        verify(restScheduleStore, never()).updateSchedule(anyString(), any());
        verify(restScheduleStore).createSchedule(any());
    }

    /**
     * The rollback is what makes a merge safe to retry. When it, too, cannot write,
     * it says so and lets the original failure stand — a rollback error must never
     * mask the error that caused it.
     */
    @Test
    @DisplayName("a rollback that cannot restore the overwritten schedule does not mask the original failure")
    void failingRollbackDoesNotMaskTheOriginalFailure() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            writeSchedule(dir, "sched1", "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"other\"}");
            writeSchedule(dir, "sched2", "{\"id\":\"sched2\",\"name\":\"hourly\",\"agentId\":\"other\"}");
        });
        stubExistingAgentForMerge();

        var existing = new ScheduleConfiguration();
        existing.setId("alreadyHere");
        existing.setName("nightly");
        when(scheduleStore.readSchedulesByAgentId(NEW_AGENT_ID)).thenReturn(List.of(existing));
        when(scheduleStore.readSchedule("alreadyHere")).thenReturn(existing);
        // The second archived schedule has no match, so it is created — and refused.
        doReturn(Response.status(400).entity("invalid cron").build())
                .when(restScheduleStore).createSchedule(any());
        doAnswer(inv -> {
            throw new IllegalStateException("restore unavailable");
        }).when(scheduleStore).updateSchedule(eq("alreadyHere"), any());

        var thrown = assertThrows(WebApplicationException.class, () -> runImport("merge", null));

        assertEquals(400, thrown.getResponse().getStatus(),
                "the caller must see why the import failed, not why the rollback did");
        // The rollback was attempted through the raw store, which re-applies no
        // defaults and recomputes no nextFire.
        verify(scheduleStore).updateSchedule(eq("alreadyHere"), any());
    }

    /**
     * A schedule's {@code userId} is the identity every fire acts as. The archive's
     * value is kept only when it already names the importing caller; a merge that
     * finds no identity to carry keeps the one the target had, so a re-promotion
     * cannot strip an owner an operator assigned here.
     */
    @Test
    @DisplayName("a merge keeps the importer's own identity and does not adopt the target's")
    void mergeKeepsTheImportersOwnIdentity() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            writeSchedule(dir, "sched1",
                    "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"other\",\"userId\":\"alice\"}");
        });
        stubExistingAgentForMerge();
        when(spaceContext.currentPrincipal()).thenReturn("alice");

        var existing = new ScheduleConfiguration();
        existing.setId("alreadyHere");
        existing.setName("nightly");
        existing.setUserId("bob");
        when(scheduleStore.readSchedulesByAgentId(NEW_AGENT_ID)).thenReturn(List.of(existing));
        when(scheduleStore.readSchedule("alreadyHere")).thenReturn(existing);

        runImport("merge", null);

        var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(restScheduleStore, times(1)).updateSchedule(eq("alreadyHere"), captor.capture());
        assertEquals("alice", captor.getValue().getUserId(),
                "an identity that already names the importer is the one case the archive's value is known to be right");
    }

    /**
     * With no identity in the archive at all there is nothing to carry over and
     * nothing to reject — the schedule surface files it under the system scheduler,
     * and the import must not invent an owner on the way.
     */
    @Test
    @DisplayName("a schedule with a blank userId is imported without one")
    void blankUserIdIsLeftAlone() throws Exception {
        stubUnzip(dir -> {
            writeAgent(dir);
            writeSchedule(dir, "sched1",
                    "{\"id\":\"sched1\",\"name\":\"nightly\",\"agentId\":\"other\",\"userId\":\"\"}");
        });
        when(spaceContext.currentPrincipal()).thenReturn("alice");

        runImport("create", null);

        var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(restScheduleStore).createSchedule(captor.capture());
        assertEquals("", captor.getValue().getUserId(),
                "there is no foreign identity to reject, so nothing is rewritten");
    }

    // ==================== Helpers ====================

    private Response runImport(String strategy, String selectedResources) throws Exception {
        var agentStore = mock(IAgentStore.class);
        when(agentStore.create(any())).thenReturn(resourceId(NEW_AGENT_ID, 1));
        when(documentDescriptorStore.getCurrentResourceId(NEW_AGENT_ID)).thenReturn(resourceId(NEW_AGENT_ID, 1));
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + NEW_AGENT_ID + "?version=1"));
        when(documentDescriptorStore.readDescriptor(NEW_AGENT_ID, 1)).thenReturn(descriptor);

        var restAgentStore = mock(IRestAgentStore.class);
        when(restAgentStore.updateAgent(anyString(), anyInt(), any())).thenReturn(Response.ok().build());

        try (var cdi = stubCdi(IAgentStore.class, agentStore,
                IRestScheduleStore.class, restScheduleStore,
                IRestAgentStore.class, restAgentStore)) {
            return importService.importAgent(new ByteArrayInputStream(new byte[0]), strategy,
                    selectedResources, null, null);
        }
    }

    /** Makes the archived agent match an agent this deployment already has. */
    private void stubExistingAgentForMerge() throws Exception {
        var existingDescriptor = new DocumentDescriptor();
        existingDescriptor.setResource(URI.create(
                "eddi://ai.labs.agent/agentstore/agents/" + NEW_AGENT_ID + "?version=4"));
        when(documentDescriptorStore.findByOriginId(AGENT_ORIGIN_ID))
                .thenReturn(List.of(existingDescriptor));
    }

    private static void writeAgent(File dir) throws IOException {
        Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "{\"workflows\":[]}");
    }

    private static void writeSchedule(File dir, String fileName, String json) throws IOException {
        File schedules = new File(dir, "schedules");
        assertTrue(schedules.mkdirs() || schedules.isDirectory());
        Files.writeString(new File(schedules, fileName + ".schedule.json").toPath(), json);
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
