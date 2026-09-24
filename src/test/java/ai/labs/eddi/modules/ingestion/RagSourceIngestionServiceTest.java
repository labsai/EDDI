/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.modules.ingestion.files.InMemoryIngestedFileStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.IngestionReport;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.Mode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RagSourceIngestionService} — when a source runs, and keeping its
 * schedule in step with its configuration.
 */
class RagSourceIngestionServiceTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final String SOURCE_ID = "src-1";

    private IngestionPipeline pipeline;
    private InMemoryIngestionStateStore stateStore;
    private IScheduleStore scheduleStore;
    private IRagStore ragStore;
    private InMemoryIngestedFileStore fileStore;
    private RagSourceIngestionService service;

    @BeforeEach
    void setUp() {
        pipeline = mock(IngestionPipeline.class);
        stateStore = new InMemoryIngestionStateStore();
        scheduleStore = mock(IScheduleStore.class);
        ragStore = mock(IRagStore.class);
        fileStore = new InMemoryIngestedFileStore();
        service = new RagSourceIngestionService(pipeline, stateStore, scheduleStore, ragStore, fileStore);
        // The reservation is the real one, against the real store: with a bare mock it
        // returns an empty Optional and every runAsync assertion below passes for the
        // wrong reason.
        when(pipeline.reserveRun(anyString(), any())).thenAnswer(invocation -> stateStore
                .startRun(IngestionPipeline.stateKey(invocation.getArgument(0), invocation.getArgument(1))));
    }

    private static IngestionSource source(String cron) {
        var web = new IngestionSource.WebSource();
        web.setStartUrl("https://example.com/");

        var source = new IngestionSource();
        source.setId(SOURCE_ID);
        source.setName("docs");
        source.setWeb(web);
        source.setCron(cron);
        return source;
    }

    private static RagConfiguration knowledgeBase(IngestionSource... sources) {
        var config = new RagConfiguration();
        config.setName("product-docs");
        config.setSources(List.of(sources));
        return config;
    }

    @Nested
    @DisplayName("schedules")
    class Schedules {

        @Test
        @DisplayName("creates one schedule per source that has a cron, under its deterministic name")
        void createsScheduleForCron() throws Exception {
            var source = source("0 2 * * *");

            service.syncSchedules(KB_ID, 1, knowledgeBase(source), Set.of());

            ArgumentCaptor<ScheduleConfiguration> captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createSchedule(captor.capture());
            ScheduleConfiguration schedule = captor.getValue();
            assertEquals(RagIngestionSchedules.scheduleName(KB_ID, SOURCE_ID), schedule.getName());
            assertEquals("0 2 * * *", schedule.getCronExpression());
            assertTrue(RagIngestionSchedules.isIngestionSchedule(schedule.getMetadata()));
            assertEquals(KB_ID, RagIngestionSchedules.ragConfigId(schedule.getMetadata()));
            assertEquals(SOURCE_ID, RagIngestionSchedules.sourceId(schedule.getMetadata()));
        }

        @Test
        @DisplayName("deletes the old schedule first, so syncing twice leaves exactly one")
        void syncIsAnUpsert() throws Exception {
            // Deterministic name + delete-then-create. The draft searched
            // readAllSchedules(1000) instead, which past a thousand schedules failed to
            // find the row and created a duplicate.
            var source = source("0 2 * * *");

            service.syncSchedules(KB_ID, 1, knowledgeBase(source), Set.of());
            service.syncSchedules(KB_ID, 1, knowledgeBase(source), Set.of());

            verify(scheduleStore, times(2))
                    .deleteSchedulesByName(RagIngestionSchedules.scheduleName(KB_ID, SOURCE_ID));
        }

        @Test
        @DisplayName("a source with no cron gets no schedule, but any old one is still removed")
        void noCronMeansNoSchedule() throws Exception {
            service.syncSchedules(KB_ID, 1, knowledgeBase(source(null)), Set.of());

            verify(scheduleStore).deleteSchedulesByName(RagIngestionSchedules.scheduleName(KB_ID, SOURCE_ID));
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("a disabled source stops running rather than keeping its schedule")
        void disabledSourceIsUnscheduled() throws Exception {
            var source = source("0 2 * * *");
            source.setEnabled(false);

            service.syncSchedules(KB_ID, 1, knowledgeBase(source), Set.of());

            verify(scheduleStore).deleteSchedulesByName(RagIngestionSchedules.scheduleName(KB_ID, SOURCE_ID));
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("a schedule that could not be written is surfaced, not swallowed")
        void scheduleFailureIsSurfaced() throws Exception {
            // The draft returned 201 with its schedule creation silently failed, leaving
            // a source that looked scheduled and never ran.
            when(scheduleStore.createSchedule(any())).thenThrow(new IResourceStore.ResourceStoreException("nope"));

            assertThrows(IllegalStateException.class,
                    () -> service.syncSchedules(KB_ID, 1, knowledgeBase(source("0 2 * * *")), Set.of()));
        }

        @Test
        @DisplayName("deleting a knowledge base removes its sources' schedules")
        void removesSchedulesOnDelete() throws Exception {
            service.removeSchedules(KB_ID, knowledgeBase(source("0 2 * * *")));

            verify(scheduleStore).deleteSchedulesByName(RagIngestionSchedules.scheduleName(KB_ID, SOURCE_ID));
        }

        @Test
        @DisplayName("removing schedules tolerates a store failure rather than blocking the delete")
        void removeToleratesStoreFailure() throws Exception {
            when(scheduleStore.deleteSchedulesByName(anyString()))
                    .thenThrow(new IResourceStore.ResourceStoreException("nope"));

            service.removeSchedules(KB_ID, knowledgeBase(source("0 2 * * *")));
        }

        @Test
        @DisplayName("a knowledge base with no sources needs no schedule work")
        void noSourcesIsANoOp() throws Exception {
            var empty = new RagConfiguration();
            empty.setSources(null);

            service.syncSchedules(KB_ID, 1, empty, Set.of());
            service.removeSchedules(KB_ID, empty);

            verify(scheduleStore, never()).createSchedule(any());
        }
    }

    @Nested
    @DisplayName("review findings")
    class ReviewFindings {

        @Test
        @DisplayName("a source removed from the knowledge base loses its schedule")
        void removedSourceLosesItsSchedule() throws Exception {
            // syncSchedules only walked the NEW document's sources, so a source deleted
            // from sources[] kept its schedule — still naming the old version, still
            // crawling a third party on a cron, with nothing in the configuration left
            // to show for it or to switch it off.
            var remaining = source("0 2 * * *");
            remaining.setId("src-keep");

            service.syncSchedules(KB_ID, 2, knowledgeBase(remaining), Set.of("src-keep", "src-removed"));

            verify(scheduleStore).deleteSchedulesByName(RagIngestionSchedules.scheduleName(KB_ID, "src-removed"));
        }

        @Test
        @DisplayName("a source that is still present keeps exactly one schedule")
        void survivingSourceIsNotDoubleDeleted() throws Exception {
            var source = source("0 2 * * *");

            service.syncSchedules(KB_ID, 2, knowledgeBase(source), Set.of(SOURCE_ID));

            // Once for the upsert, and NOT a second time as a supposed removal.
            verify(scheduleStore, times(1))
                    .deleteSchedulesByName(RagIngestionSchedules.scheduleName(KB_ID, SOURCE_ID));
        }

        @Test
        @DisplayName("a preview cannot block its caller for the source's full time budget")
        void previewIsCapped() {
            // Preview is synchronous and unguarded by the single-in-flight rule, so an
            // uncapped one holds a request thread for up to a day per click while
            // sending that much traffic to a third party.
            var generous = source(null);
            var settings = new IngestionSource.IngestionSettings();
            settings.setTimeBudgetMinutes(1440);
            generous.setSettings(settings);
            when(pipeline.run(anyString(), any(), any(), eq(Mode.PREVIEW)))
                    .thenReturn(IngestionReport.skipped(SOURCE_ID, "stub"));

            service.preview(KB_ID, knowledgeBase(generous), generous);

            ArgumentCaptor<IngestionSource> used = ArgumentCaptor.forClass(IngestionSource.class);
            verify(pipeline).run(anyString(), any(), used.capture(), eq(Mode.PREVIEW));
            assertTrue(used.getValue().settings().timeBudgetMinutesOrDefault() <= 2,
                    "preview budget was " + used.getValue().settings().timeBudgetMinutesOrDefault() + " minutes");
        }

        @Test
        @DisplayName("a preview never runs against the source's own schedule or identity")
        void previewDoesNotInheritCron() {
            var scheduled = source("0 2 * * *");
            when(pipeline.run(anyString(), any(), any(), eq(Mode.PREVIEW)))
                    .thenReturn(IngestionReport.skipped(SOURCE_ID, "stub"));

            service.preview(KB_ID, knowledgeBase(scheduled), scheduled);

            ArgumentCaptor<IngestionSource> used = ArgumentCaptor.forClass(IngestionSource.class);
            verify(pipeline).run(anyString(), any(), used.capture(), eq(Mode.PREVIEW));
            assertEquals(null, used.getValue().getCron());
            assertEquals(SOURCE_ID, used.getValue().getId(), "state must still be keyed the same way");
        }
    }

    /**
     * The rows {@code createSchedule} was handed, and what {@code findDueSchedules}
     * would make of them.
     *
     * <p>
     * The filter below is the one both stores run, transcribed: {@code enabled =
     * true AND nextFire <= now AND fireStatus = PENDING}. It is spelled out here
     * rather than mocked away because the whole defect lives in that comparison — a
     * schedule created without a {@code nextFire} is stored looking enabled and is
     * never selected, on either backend, for ever.
     */
    private static final class StoredSchedules {

        private final List<ScheduleConfiguration> rows = new ArrayList<>();

        String record(ScheduleConfiguration schedule) {
            schedule.setId(UUID.randomUUID().toString());
            rows.add(schedule);
            return schedule.getId();
        }

        ScheduleConfiguration only() {
            assertEquals(1, rows.size(), "expected exactly one schedule to have been created");
            return rows.get(0);
        }

        List<ScheduleConfiguration> due(Instant now) {
            return rows.stream()
                    .filter(row -> row.isEnabled()
                            && row.getNextFire() != null
                            && !row.getNextFire().isAfter(now)
                            && row.getFireStatus() == ScheduleConfiguration.FireStatus.PENDING)
                    .toList();
        }
    }

    private StoredSchedules recordingStore() throws Exception {
        var stored = new StoredSchedules();
        when(scheduleStore.createSchedule(any()))
                .thenAnswer(invocation -> stored.record(invocation.getArgument(0)));
        return stored;
    }

    @Nested
    @DisplayName("arming the schedule")
    class Arming {

        @Test
        @DisplayName("a source with a cron is due once its fire time arrives")
        void scheduledSourceBecomesDue() throws Exception {
            var stored = recordingStore();

            service.syncSchedules(KB_ID, 1, knowledgeBase(source("0 2 * * *")), Set.of());

            ScheduleConfiguration schedule = stored.only();
            Instant fireTime = schedule.getNextFire();
            assertNotNull(fireTime, "a schedule stored without a next fire time can never be selected by a poll");
            assertTrue(stored.due(fireTime.minusSeconds(60)).isEmpty(), "it must not be due before its time");
            assertEquals(List.of(schedule), stored.due(fireTime),
                    "the schedule must come back from findDueSchedules once its time arrives");
            assertEquals(List.of(schedule), stored.due(fireTime.plusSeconds(3600)),
                    "a poll that ran late must still find it");
        }

        @Test
        @DisplayName("the fire time is the cron read in UTC, and the schedule says so")
        void armedInUtc() throws Exception {
            // The poller re-arms in resolveTimeZone(schedule.getTimeZone()), which falls
            // back to eddi.schedule.default-timezone. Leaving the zone unset would mean
            // the first fire and every later one were computed in different zones on any
            // deployment that sets it.
            var stored = recordingStore();

            service.syncSchedules(KB_ID, 1, knowledgeBase(source("0 2 * * *")), Set.of());

            ScheduleConfiguration schedule = stored.only();
            assertEquals("UTC", schedule.getTimeZone());
            var fireTime = schedule.getNextFire().atZone(ZoneId.of("UTC"));
            assertEquals(2, fireTime.getHour());
            assertEquals(0, fireTime.getMinute());
            assertTrue(schedule.getNextFire().isAfter(Instant.now()), "the first fire is in the future");
        }

        @Test
        @DisplayName("a cron that can never match is refused rather than stored unfired")
        void unsatisfiableCronIsRefused() throws Exception {
            // "0 0 30 2 *" parses — CronParser.validate accepts it — and matches no day
            // in any year. Stored, it is a source that shows as scheduled for ever.
            var source = source("0 0 30 2 *");

            assertThrows(IllegalArgumentException.class,
                    () -> service.syncSchedules(KB_ID, 1, knowledgeBase(source), Set.of()));
            verify(scheduleStore, never()).createSchedule(any());
        }
    }

    @Nested
    @DisplayName("startup repair of schedules stored before they were armed")
    class StartupRepair {

        private ScheduleConfiguration unarmedIngestionSchedule() {
            var schedule = new ScheduleConfiguration();
            schedule.setId("sched-1");
            schedule.setName(RagIngestionSchedules.scheduleName(KB_ID, SOURCE_ID));
            schedule.setTriggerType(ScheduleConfiguration.TriggerType.CRON);
            schedule.setCronExpression("0 2 * * *");
            schedule.setEnabled(true);
            schedule.setMetadata(RagIngestionSchedules.metadata(KB_ID, 1, SOURCE_ID));
            return schedule;
        }

        private void storeHolds(ScheduleConfiguration... schedules) throws Exception {
            when(scheduleStore.readAllSchedules(anyInt(), anyInt(), anyBoolean()))
                    .thenAnswer(invocation -> (int) invocation.getArgument(1) == 0
                            ? List.of(schedules)
                            : List.of());
            // The conditional write succeeds by default — Mockito's own default for a
            // boolean is false, which would model a store where every node always
            // loses the race and nothing is ever armed. The test that cares about
            // losing it overrides this.
            when(scheduleStore.armIfUnarmed(anyString(), any())).thenReturn(true);
        }

        @Test
        @DisplayName("an ingestion schedule with no fire time is given one")
        void armsTheBrokenRow() throws Exception {
            storeHolds(unarmedIngestionSchedule());

            service.repairUnarmedSchedules();

            ArgumentCaptor<Instant> fireTime = ArgumentCaptor.forClass(Instant.class);
            verify(scheduleStore).armIfUnarmed(eq("sched-1"), fireTime.capture());
            assertNotNull(fireTime.getValue());
            assertEquals(2, fireTime.getValue().atZone(ZoneId.of("UTC")).getHour());
        }

        @Test
        @DisplayName("the write is conditional, so a second node cannot move a fire time already set")
        void armsOnlyWhileStillUnarmed() throws Exception {
            // Each node computes its own occurrence from its own clock, so across a
            // cron boundary they differ — an unconditional write let the slower node
            // replace the earlier fire with the later one and skip it. The condition
            // belongs in the store's predicate, which is the only place both nodes
            // meet.
            storeHolds(unarmedIngestionSchedule());

            service.repairUnarmedSchedules();

            verify(scheduleStore).armIfUnarmed(eq("sched-1"), any());
            verify(scheduleStore, never()).setScheduleEnabled(anyString(), anyBoolean(), any());
        }

        @Test
        @DisplayName("running it again changes nothing, because nothing is unarmed any more")
        void isIdempotent() throws Exception {
            var repaired = unarmedIngestionSchedule();
            repaired.setNextFire(Instant.now().plusSeconds(3600));
            storeHolds(repaired);

            service.repairUnarmedSchedules();

            verify(scheduleStore, never()).armIfUnarmed(anyString(), any());
        }

        @Test
        @DisplayName("schedules that are not this feature's are left alone")
        void leavesOtherSchedulesAlone() throws Exception {
            var foreign = unarmedIngestionSchedule();
            foreign.setMetadata(Map.of("hitlType", "hitl_timeout"));
            var disabled = unarmedIngestionSchedule();
            disabled.setId("sched-2");
            disabled.setEnabled(false);
            var cronless = unarmedIngestionSchedule();
            cronless.setId("sched-3");
            cronless.setCronExpression(null);
            storeHolds(foreign, disabled, cronless);

            service.repairUnarmedSchedules();

            verify(scheduleStore, never()).armIfUnarmed(anyString(), any());
        }

        @Test
        @DisplayName("a store that cannot be read does not stop the application starting")
        void survivesAStoreFailure() throws Exception {
            when(scheduleStore.readAllSchedules(anyInt(), anyInt(), anyBoolean()))
                    .thenThrow(new IResourceStore.ResourceStoreException("nope"));

            service.repairUnarmedSchedules();

            verify(scheduleStore, never()).armIfUnarmed(anyString(), any());
        }

        @Test
        @DisplayName("the sweep can be turned off")
        void canBeDisabled() throws Exception {
            service.scheduleRepairEnabled = false;
            storeHolds(unarmedIngestionSchedule());

            service.repairUnarmedSchedules();

            verify(scheduleStore, never()).readAllSchedules(anyInt(), anyInt(), anyBoolean());
            assertFalse(service.scheduleRepairEnabled);
        }

        /**
         * Review finding (Copilot, #818): the listing this sweep walks is a snapshot,
         * and {@code setScheduleEnabled} overwrote {@code nextFire} unconditionally.
         * Two nodes booting together both see the row as unarmed, and the slower one's
         * later {@code Instant.now()} replaced the first node's occurrence with the
         * following one — a skipped fire. The condition now lives in the store's write
         * predicate, so the second node's write matches nothing and it is told so.
         */
        @Test
        @DisplayName("a row another node armed while the sweep was listing is left alone")
        void doesNotOverwriteARowAnotherNodeAlreadyArmed() throws Exception {
            storeHolds(unarmedIngestionSchedule());
            // What the store reports when its "still unarmed" predicate matched nothing
            // — the row acquired a fire time between the listing and this write.
            when(scheduleStore.armIfUnarmed(anyString(), any())).thenReturn(false);

            var result = assertDoesNotThrow(() -> service.repairUnarmedSchedules());

            verify(scheduleStore, never()).setScheduleEnabled(anyString(), anyBoolean(), any());
            assertEquals(0, result.armed(),
                    "a row somebody else armed is not one this sweep repaired, and counting it would "
                            + "report work that did not happen");
            assertTrue(result.complete(), "losing the race is not a reason to call the sweep unfinished");
        }

        /**
         * Review finding (Copilot, #818): the page bound is a deliberate safety limit,
         * but it used to stop the walk without saying so — an operator read "armed 12
         * schedules" and could not tell a finished repair from one that stopped a page
         * short of the row they were waiting on.
         */
        @Test
        @DisplayName("stopping at the page bound is reported, not swallowed")
        void aTruncatedSweepSaysSo() throws Exception {
            var armed = unarmedIngestionSchedule();
            armed.setNextFire(Instant.now().plusSeconds(3600));
            // Every page full, for ever: the walk can only end at its own bound.
            when(scheduleStore.readAllSchedules(anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(Collections.nCopies(RagSourceIngestionService.REPAIR_PAGE_SIZE, armed));

            var result = service.repairUnarmedSchedules();

            assertFalse(result.complete(),
                    "the sweep stopped at its own bound rather than at the end of the data, and the "
                            + "difference is the whole point: rows beyond it were never examined");
            verify(scheduleStore, times(RagSourceIngestionService.REPAIR_MAX_PAGES))
                    .readAllSchedules(anyInt(), anyInt(), anyBoolean());
        }

        /**
         * Review finding (CodeRabbit, #818): the repair arms through
         * {@code armIfUnarmed}, which writes {@code nextFire} and nothing else — a
         * legacy row's null {@code timeZone} stays null. Every fire after the first is
         * therefore re-armed by the poller through {@code resolveTimeZone(null)}, the
         * deployment default, so arming the first one in UTC regardless would hand a
         * non-UTC deployment exactly one interval of the wrong length. That is the same
         * drift {@code buildSchedule}'s {@code setTimeZone} was fixed for, arriving by
         * the back door.
         */
        @Test
        @DisplayName("a legacy row with no zone is armed in the zone the poller will use, not in UTC")
        void armsLegacyRowsInThePollerZone() throws Exception {
            service.defaultTimeZone = "Asia/Tokyo";
            var schedule = unarmedIngestionSchedule();
            schedule.setCronExpression("0 2 * * *");
            schedule.setTimeZone(null);
            storeHolds(schedule);

            service.repairUnarmedSchedules();

            var fireTime = ArgumentCaptor.forClass(Instant.class);
            verify(scheduleStore).armIfUnarmed(eq("sched-1"), fireTime.capture());
            assertEquals(2, fireTime.getValue().atZone(ZoneId.of("Asia/Tokyo")).getHour(),
                    "02:00 means 02:00 in the zone this row will be re-armed in; computing it in UTC "
                            + "would make the first interval the odd one out on every deployment that "
                            + "sets a time zone");
        }

        @Test
        @DisplayName("a row that names its own zone is armed in that zone")
        void armsInTheRowsOwnZoneWhenItHasOne() throws Exception {
            service.defaultTimeZone = "Asia/Tokyo";
            var schedule = unarmedIngestionSchedule();
            schedule.setCronExpression("0 2 * * *");
            schedule.setTimeZone("UTC");
            storeHolds(schedule);

            service.repairUnarmedSchedules();

            var fireTime = ArgumentCaptor.forClass(Instant.class);
            verify(scheduleStore).armIfUnarmed(eq("sched-1"), fireTime.capture());
            assertEquals(2, fireTime.getValue().atZone(ZoneId.of("UTC")).getHour(),
                    "the row's own zone is what the poller resolves first, so it is what the repair "
                            + "must arm in");
        }

        @Test
        @DisplayName("reaching the end of the data is reported as a complete sweep")
        void aFullSweepSaysSo() throws Exception {
            storeHolds(unarmedIngestionSchedule());

            var result = service.repairUnarmedSchedules();

            assertTrue(result.complete(), "a short page is the end of the data, and that is a finished sweep");
            assertEquals(1, result.armed());
        }
    }

    @Nested
    @DisplayName("scheduled fire")
    class ScheduledFire {

        @Test
        @DisplayName("loads the knowledge base and runs the named source")
        void runsTheNamedSource() throws Exception {
            var source = source("0 2 * * *");
            when(ragStore.read(eq(KB_ID), anyInt())).thenReturn(knowledgeBase(source));
            when(pipeline.run(anyString(), any(), any(), eq(Mode.INGEST)))
                    .thenReturn(IngestionReport.skipped(SOURCE_ID, "stub"));

            service.processScheduledFire(KB_ID, 1, SOURCE_ID);

            verify(pipeline).run(eq(KB_ID), any(RagConfiguration.class), any(IngestionSource.class), eq(Mode.INGEST));
        }

        @Test
        @DisplayName("a source deleted while its schedule was in flight fails the fire loudly")
        void missingSourceFailsTheFire() throws Exception {
            when(ragStore.read(eq(KB_ID), anyInt())).thenReturn(knowledgeBase(source("0 2 * * *")));

            IngestionReport report = service.processScheduledFire(KB_ID, 1, "gone");

            assertEquals(IngestionReport.Outcome.FAILED, report.outcome());
            assertTrue(report.message().contains("gone"), report.message());
        }

        @Test
        @DisplayName("a deleted knowledge base fails the fire rather than throwing into the scheduler")
        void missingKnowledgeBaseFailsTheFire() throws Exception {
            when(ragStore.read(eq(KB_ID), anyInt()))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("gone"));

            IngestionReport report = service.processScheduledFire(KB_ID, 1, SOURCE_ID);

            assertEquals(IngestionReport.Outcome.FAILED, report.outcome());
        }
    }

    @Nested
    @DisplayName("manual runs")
    class ManualRuns {

        @Test
        @DisplayName("refuses a run while one is already in flight")
        void refusesConcurrentRun() {
            var source = source(null);
            stateStore.startRun(IngestionPipeline.stateKey(KB_ID, source));

            assertTrue(service.runAsync(KB_ID, knowledgeBase(source), source).isEmpty(),
                    "five clicks on 'run now' must not become five crawls");
        }

        @Test
        @DisplayName("the run is claimed before the worker starts, and its id is returned")
        void reservesBeforeStartingTheWorker() {
            // The claim used to happen inside the worker, so two requests arriving
            // together were both told "started" and one of them crawled nothing.
            var source = source(null);
            String sourceKey = IngestionPipeline.stateKey(KB_ID, source);

            var runId = service.runAsync(KB_ID, knowledgeBase(source), source);

            assertTrue(runId.isPresent());
            var active = stateStore.activeRun(sourceKey);
            assertTrue(active.isPresent(), "the run must be claimed by the time the caller is answered");
            assertEquals(runId.get(), active.get().runId(), "the caller gets the run id, not the source key");
            assertTrue(service.runAsync(KB_ID, knowledgeBase(source), source).isEmpty(),
                    "a second request must be refused by the reservation, not by a later claim");
        }

        @Test
        @DisplayName("a preview delegates to the pipeline in preview mode")
        void previewUsesPreviewMode() {
            var source = source(null);
            when(pipeline.run(anyString(), any(), any(), eq(Mode.PREVIEW)))
                    .thenReturn(IngestionReport.skipped(SOURCE_ID, "stub"));

            service.preview(KB_ID, knowledgeBase(source), source);

            verify(pipeline).run(eq(KB_ID), any(), any(), eq(Mode.PREVIEW));
        }

        @Test
        @DisplayName("purging forgets only this source's state")
        void purgeIsScopedToTheSource() {
            var source = source(null);
            String key = IngestionPipeline.stateKey(KB_ID, source);
            String otherKey = IngestionPipeline.stateKey("other-kb", source);
            String runId = stateStore.startRun(key).orElseThrow();
            stateStore.recordIngested(key, "doc", "hash", null, null, runId);
            String otherRunId = stateStore.startRun(otherKey).orElseThrow();
            stateStore.recordIngested(otherKey, "doc", "hash", null, null, otherRunId);

            service.purge(KB_ID, source);

            assertTrue(stateStore.lookup(key, "doc").isEmpty());
            assertTrue(stateStore.lookup(otherKey, "doc").isPresent(), "another knowledge base must be untouched");
        }

        @Test
        @DisplayName("run history is read for this source's key")
        void listsRunsForTheSource() {
            var source = source(null);
            String key = IngestionPipeline.stateKey(KB_ID, source);
            stateStore.startRun(key);

            assertEquals(1, service.listRuns(KB_ID, source, 10).size());
        }
    }

    @Nested
    @DisplayName("a source that stops owning its documents")
    class RemovedSources {

        private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";

        private IngestionSource uploadSource(String id) {
            var source = new IngestionSource();
            source.setId(id);
            source.setName("handbooks");
            source.setType(IngestionSource.TYPE_UPLOAD);
            return source;
        }

        private RagConfiguration knowledgeBase(IngestionSource... sources) {
            var config = new RagConfiguration();
            config.setName("product-docs");
            config.setSources(List.of(sources));
            return config;
        }

        private String keyOf(IngestionSource source) {
            return IngestionPipeline.stateKey(KB_ID, source);
        }

        @Test
        @DisplayName("removing it takes its files AND what the knowledge base learned from them")
        void removingASourceTakesBoth() {
            var source = uploadSource("src-files");
            fileStore.store(keyOf(source), "handbook.pdf", "application/pdf", "x".getBytes(UTF_8));
            var before = knowledgeBase(source);

            service.discardRemovedSources(KB_ID, before, knowledgeBase());

            // Deleting the files and leaving the vectors is the worst of the three
            // outcomes: agents keep citing a document the operator believes is gone,
            // and no endpoint can list or delete it, because the source it belonged
            // to is no longer in the configuration.
            verify(pipeline).forgetSource(eq(KB_ID), any(), argThat(s -> "src-files".equals(s.getId())));
            assertTrue(fileStore.list(keyOf(source)).isEmpty());
        }

        @Test
        @DisplayName("turning it into a crawl takes them too")
        void changingItsTypeTakesBoth() {
            var source = uploadSource("src-files");
            fileStore.store(keyOf(source), "handbook.pdf", "application/pdf", "x".getBytes(UTF_8));

            var web = new IngestionSource.WebSource();
            web.setStartUrl("https://example.com/");
            var nowACrawl = new IngestionSource();
            nowACrawl.setId("src-files");
            nowACrawl.setName("handbooks");
            nowACrawl.setWeb(web);

            service.discardRemovedSources(KB_ID, knowledgeBase(source), knowledgeBase(nowACrawl));

            // The id survives the change, so nothing else notices — and the files
            // become unreachable, since the file endpoints refuse a source that is
            // not an upload source.
            verify(pipeline).forgetSource(eq(KB_ID), any(), any());
            assertTrue(fileStore.list(keyOf(source)).isEmpty());
        }

        @Test
        @DisplayName("leaves a source that is still there alone")
        void keepsWhatIsStillThere() {
            var source = uploadSource("src-files");
            fileStore.store(keyOf(source), "handbook.pdf", "application/pdf", "x".getBytes(UTF_8));

            service.discardRemovedSources(KB_ID, knowledgeBase(source), knowledgeBase(uploadSource("src-files")));

            verify(pipeline, never()).forgetSource(anyString(), any(), any());
            assertEquals(1, fileStore.list(keyOf(source)).size());
        }

        @Test
        @DisplayName("says nothing about a crawl, which owns no files of its own")
        void ignoresCrawlSources() {
            var web = new IngestionSource.WebSource();
            web.setStartUrl("https://example.com/");
            var crawl = new IngestionSource();
            crawl.setId("src-web");
            crawl.setName("docs");
            crawl.setWeb(web);

            service.discardRemovedSources(KB_ID, knowledgeBase(crawl), knowledgeBase());

            // A crawl's documents come back on the next run against the same site;
            // dropping the source is not a statement that the site is wrong.
            verify(pipeline, never()).forgetSource(anyString(), any(), any());
        }

        @Test
        @DisplayName("deleting the knowledge base takes its upload sources with it")
        void deletingTheKnowledgeBaseTakesFilesToo() {
            var source = uploadSource("src-files");
            fileStore.store(keyOf(source), "handbook.pdf", "application/pdf", "x".getBytes(UTF_8));

            service.removeSchedules(KB_ID, knowledgeBase(source));

            verify(pipeline).forgetSource(eq(KB_ID), any(), any());
            assertTrue(fileStore.list(keyOf(source)).isEmpty());
        }

        @Test
        @DisplayName("a vector store that refuses still lets the files go, loudly")
        void survivesAVectorStoreThatRefuses() {
            var source = uploadSource("src-files");
            fileStore.store(keyOf(source), "handbook.pdf", "application/pdf", "x".getBytes(UTF_8));
            doThrow(new IllegalStateException("vector store is unwell"))
                    .when(pipeline).forgetSource(anyString(), any(), any());

            service.discardRemovedSources(KB_ID, knowledgeBase(source), knowledgeBase());

            // Refusing to delete the files because the vectors could not go would
            // leave the operator with neither the content removed nor a way to try
            // again — the configuration has already been written.
            assertTrue(fileStore.list(keyOf(source)).isEmpty());
        }
    }
}
