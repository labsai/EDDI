/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.IngestionSource;
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

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private RagSourceIngestionService service;

    @BeforeEach
    void setUp() {
        pipeline = mock(IngestionPipeline.class);
        stateStore = new InMemoryIngestionStateStore();
        scheduleStore = mock(IScheduleStore.class);
        ragStore = mock(IRagStore.class);
        service = new RagSourceIngestionService(pipeline, stateStore, scheduleStore, ragStore);
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
}
