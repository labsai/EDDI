/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.modules.ingestion.files.InMemoryIngestedFileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the source card shows about a source's schedule, and the cron preview
 * the editor checks as the operator types.
 */
class RagSourceScheduleViewTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";

    private IScheduleStore scheduleStore;
    private RagSourceIngestionService service;

    @BeforeEach
    void setUp() {
        scheduleStore = mock(IScheduleStore.class);
        service = new RagSourceIngestionService(mock(IngestionPipeline.class), new InMemoryIngestionStateStore(),
                scheduleStore, mock(IRagStore.class), new InMemoryIngestedFileStore());
    }

    private static IngestionSource source() {
        var source = new IngestionSource();
        source.setId("src-1");
        source.setName("docs");
        return source;
    }

    @Test
    @DisplayName("an unscheduled source reports nothing, and reads no fire log")
    void unscheduledSource() throws Exception {
        when(scheduleStore.readScheduleByName(anyString())).thenReturn(Optional.empty());

        var status = service.scheduleStatus(KB_ID, source());

        assertNull(status.schedule());
        assertTrue(status.recentFires().isEmpty());
        verify(scheduleStore, never()).readFireLogs(anyString(), anyInt());
    }

    @Test
    @DisplayName("a scheduled source is found by its schedule name, with its recent fires")
    void scheduledSource() throws Exception {
        var schedule = new ScheduleConfiguration();
        schedule.setId("sched-1");
        when(scheduleStore.readScheduleByName(RagIngestionSchedules.scheduleName(KB_ID, "src-1")))
                .thenReturn(Optional.of(schedule));
        var fire = new ScheduleFireLog("log-1", "sched-1", "fire-1", Instant.now(), Instant.now(), Instant.now(),
                "FAILED", "node-1", null, "embedding provider returned 401", 1, 0.0);
        when(scheduleStore.readFireLogs(eq("sched-1"), eq(RagSourceIngestionService.RECENT_FIRES)))
                .thenReturn(List.of(fire));

        var status = service.scheduleStatus(KB_ID, source());

        assertEquals("sched-1", status.schedule().getId());
        assertEquals("embedding provider returned 401", status.recentFires().get(0).errorMessage());
    }

    @Test
    @DisplayName("a valid cron previews its next fires in UTC, in order")
    void validCronPreviews() {
        var preview = service.previewCron("0 2 * * *", 3);

        assertNull(preview.error());
        assertEquals("UTC", preview.zone());
        assertEquals(3, preview.nextFires().size());
        for (Instant fire : preview.nextFires()) {
            assertEquals(2, fire.atZone(ZoneOffset.UTC).getHour());
        }
        assertTrue(preview.nextFires().get(0).isBefore(preview.nextFires().get(1)));
    }

    @Test
    @DisplayName("an unparseable cron says why, without fire times")
    void invalidCron() {
        var preview = service.previewCron("every night", 3);

        assertNotNull(preview.error());
        assertTrue(preview.nextFires().isEmpty());
    }

    @Test
    @DisplayName("a cron that can never match a date is refused, as a save would refuse it")
    void impossibleCron() {
        var preview = service.previewCron("0 0 30 2 *", 3);

        assertNotNull(preview.error());
        assertTrue(preview.nextFires().isEmpty());
    }

    @Test
    @DisplayName("a cron more frequent than the deployment allows is refused, as a save would refuse it")
    void tooFrequentCron() {
        service.minIntervalSeconds = 3600;

        var preview = service.previewCron("*/5 * * * *", 3);

        assertNotNull(preview.error());
        assertTrue(preview.error().contains("eddi.schedule.min-interval-seconds"), preview.error());
    }

    @Test
    @DisplayName("a blank cron is not a schedule")
    void blankCron() {
        assertNotNull(service.previewCron(" ", 3).error());
    }
}
