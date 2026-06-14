package ai.labs.eddi.configs.ingestion.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.ingestion.IRagIngestionSourceStore;
import ai.labs.eddi.configs.ingestion.model.RagIngestionSource;
import ai.labs.eddi.configs.ingestion.model.WebSourceConfig;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.modules.ingestion.IContentHashStore;
import ai.labs.eddi.modules.ingestion.RagIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class RestRagIngestionSourceStoreTest {

    @Mock
    private IRagIngestionSourceStore sourceStore;
    @Mock
    private IScheduleStore scheduleStore;
    @Mock
    private IJsonSchemaCreator jsonSchemaCreator;
    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;
    @Mock
    private RagIngestionService ingestionService;
    @Mock
    private IContentHashStore contentHashTracker;

    private RestRagIngestionSourceStore restStore;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        when(sourceStore.update(anyString(), anyInt(), any())).thenReturn(2);
        when(scheduleStore.readAllSchedules(anyInt())).thenReturn(java.util.Collections.emptyList());
        when(scheduleStore.createSchedule(any())).thenReturn("schedule-id-123");
        restStore = new RestRagIngestionSourceStore(
                sourceStore, scheduleStore, jsonSchemaCreator,
                documentDescriptorStore, ingestionService, contentHashTracker);
    }

    private RagIngestionSource createSource(RagIngestionSource.Schedule schedule) {
        var webConfig = new WebSourceConfig(
                "https://example.com",
                new WebSourceConfig.Scope(),
                new WebSourceConfig.CrawlSettings());
        return new RagIngestionSource("test", "", "web", webConfig,
                "eddi://ai.labs.rag/ragstore/rags/fake?id=1",
                null, schedule);
    }

    @Test
    void updateIngestionSource_enabledWithInvalidCron_shouldThrow() {
        var source = createSource(new RagIngestionSource.Schedule("invalid cron", true));

        assertThrows(IllegalArgumentException.class,
                () -> restStore.updateIngestionSource("id123", 1, source));
    }

    @Test
    void updateIngestionSource_disabledWithInvalidCron_shouldNotThrow() {
        var source = createSource(new RagIngestionSource.Schedule("invalid cron", false));

        assertDoesNotThrow(() -> restStore.updateIngestionSource("id123", 1, source));
    }

    @Test
    void updateIngestionSource_enabledWithValidCron_shouldNotThrow() {
        var source = createSource(new RagIngestionSource.Schedule("0 9 * * MON-FRI", true));

        assertDoesNotThrow(() -> restStore.updateIngestionSource("id123", 1, source));
    }
}
