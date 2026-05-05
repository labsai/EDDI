/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.ingestion.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.ingestion.IRagIngestionSourceStore;
import ai.labs.eddi.configs.ingestion.IRestRagIngestionSourceStore;
import ai.labs.eddi.configs.ingestion.model.RagIngestionSource;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.modules.ingestion.ContentHashTracker;
import ai.labs.eddi.modules.ingestion.RagIngestionService;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * REST implementation for RAG ingestion source management.
 * <p>
 * Provides CRUD operations for ingestion sources, automatic schedule
 * management, and manual triggering. Supports multiple source types (web, file,
 * Git, etc.) through the pluggable ingestion pipeline.
 */
@ApplicationScoped
public class RestRagIngestionSourceStore implements IRestRagIngestionSourceStore {

    private static final Logger LOGGER = Logger.getLogger(RestRagIngestionSourceStore.class);

    private final IRagIngestionSourceStore sourceStore;
    private final IScheduleStore scheduleStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final RagIngestionService ingestionService;
    private final ContentHashTracker contentHashTracker;
    private final RestVersionInfo<RagIngestionSource> restVersionInfo;

    @Inject
    public RestRagIngestionSourceStore(
            IRagIngestionSourceStore sourceStore,
            IScheduleStore scheduleStore,
            IJsonSchemaCreator jsonSchemaCreator,
            IDocumentDescriptorStore documentDescriptorStore,
            RagIngestionService ingestionService,
            ContentHashTracker contentHashTracker) {
        this.sourceStore = sourceStore;
        this.scheduleStore = scheduleStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
        this.documentDescriptorStore = documentDescriptorStore;
        this.ingestionService = ingestionService;
        this.contentHashTracker = contentHashTracker;
        this.restVersionInfo = new RestVersionInfo<>(resourceURI, sourceStore, documentDescriptorStore);
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(RagIngestionSource.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readIngestionSourceDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.ingestion", filter, index, limit);
    }

    @Override
    public List<Map<String, Object>> findIngestionSourcesByRagConfig(String ragConfigUri, Integer index, Integer limit) {
        try {
            return sourceStore.findByRagConfigUri(ragConfigUri, index, limit);
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public RagIngestionSource readIngestionSource(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateIngestionSource(String id, Integer version, RagIngestionSource source) {
        Response response = restVersionInfo.update(id, version, source);

        // Update associated schedule
        if (response.getStatus() == Response.Status.OK.getStatusCode()) {
            try {
                updateScheduleForSource(id, source);
            } catch (Exception e) {
                LOGGER.warnf(e, "Failed to update schedule for source %s", LogSanitizer.sanitize(id));
            }
        }

        return response;
    }

    @Override
    public Response createIngestionSource(RagIngestionSource source) {
        Response response = restVersionInfo.create(source);

        // Create associated schedule
        if (response.getStatus() == Response.Status.CREATED.getStatusCode()) {
            try {
                String location = response.getLocation().toString();
                String id = extractIdFromLocation(location);
                createScheduleForSource(id, source);
            } catch (Exception e) {
                LOGGER.warnf(e, "Failed to create schedule for new source");
            }
        }

        return response;
    }

    @Override
    public Response duplicateIngestionSource(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        RagIngestionSource config = restVersionInfo.read(id, version);
        return createIngestionSource(config);
    }

    @Override
    public Response deleteIngestionSource(String id, Integer version, Boolean permanent) {
        Response response = restVersionInfo.delete(id, version, permanent);

        // Delete associated schedule
        if (response.getStatus() == Response.Status.OK.getStatusCode()) {
            try {
                deleteScheduleForSource(id);
                if (permanent) {
                    contentHashTracker.clearSource(id);
                }
            } catch (Exception e) {
                LOGGER.warnf(e, "Failed to delete schedule for source %s", LogSanitizer.sanitize(id));
            }
        }

        return response;
    }

    @Override
    public Response triggerIngestion(String id, Integer version) {
        RagIngestionSource source = restVersionInfo.read(id, version);

        // Run ingestion on a virtual thread (async)
        Thread.startVirtualThread(() -> {
            try {
                RagIngestionService.IngestionResult result = ingestionService.ingest(id, source);
                LOGGER.infof("Manual ingestion completed for source %s (type=%s): success=%s, documents=%d, chunks=%d",
                        LogSanitizer.sanitize(id), source.type(), result.isSuccess(),
                        result.documentsProcessed(), result.chunksStored());
            } catch (Exception e) {
                LOGGER.errorf(e, "Manual ingestion failed for source %s", LogSanitizer.sanitize(id));
            }
        });

        // Return 202 Accepted immediately
        return Response.accepted()
                .entity(Map.of(
                        "sourceId", id,
                        "sourceName", source.name(),
                        "sourceType", source.type(),
                        "status", "started",
                        "message", "Ingestion started asynchronously. Check logs for completion status."))
                .build();
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return sourceStore.getCurrentResourceId(id);
    }

    // --- Schedule management ---

    private void createScheduleForSource(String sourceId, RagIngestionSource source) throws Exception {
        if (!source.schedule().enabled()) {
            return;
        }

        ScheduleConfiguration schedule = new ScheduleConfiguration();
        schedule.setName("ingestion:" + source.name());
        schedule.setTriggerType(ScheduleConfiguration.TriggerType.CRON);
        schedule.setCronExpression(source.schedule().cronExpression());
        schedule.setDirectExecutionType("ingestion");
        schedule.setTimeZone("UTC");
        schedule.setEnabled(source.schedule().enabled());
        schedule.setMaxCostPerFire(-1.0);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sourceId", sourceId);
        metadata.put("sourceType", source.type());
        schedule.setMetadata(metadata);

        // Compute next fire time
        Instant nextFire = computeNextFire(schedule.getCronExpression());
        schedule.setNextFire(nextFire);

        String scheduleId = scheduleStore.createSchedule(schedule);
        LOGGER.infof("Created schedule %s for ingestion source %s (type=%s, direct execution)",
                LogSanitizer.sanitize(scheduleId), LogSanitizer.sanitize(sourceId), source.type());
    }

    private void updateScheduleForSource(String sourceId, RagIngestionSource source) {
        // Find existing schedule by metadata
        try {
            List<ScheduleConfiguration> schedules = scheduleStore.readAllSchedules(1000);
            for (ScheduleConfiguration schedule : schedules) {
                Map<String, Object> metadata = schedule.getMetadata();
                if (metadata != null && sourceId.equals(metadata.get("sourceId"))) {
                    // Update schedule
                    schedule.setName("ingestion:" + source.name());
                    schedule.setCronExpression(source.schedule().cronExpression());
                    schedule.setEnabled(source.schedule().enabled());
                    schedule.setDirectExecutionType("ingestion");

                    Map<String, Object> updatedMetadata = new HashMap<>(metadata);
                    updatedMetadata.put("sourceType", source.type());
                    schedule.setMetadata(updatedMetadata);

                    if (source.schedule().enabled() && schedule.getNextFire() == null) {
                        schedule.setNextFire(computeNextFire(schedule.getCronExpression()));
                    }

                    scheduleStore.updateSchedule(schedule.getId(), schedule);
                    LOGGER.infof("Updated schedule %s for ingestion source %s (type=%s, direct execution)",
                            LogSanitizer.sanitize(schedule.getId()),
                            LogSanitizer.sanitize(sourceId), source.type());
                    return;
                }
            }

            // No existing schedule found, create one if enabled
            if (source.schedule().enabled()) {
                createScheduleForSource(sourceId, source);
            }
        } catch (Exception e) {
            LOGGER.warnf(e, "Failed to update schedule for source %s", LogSanitizer.sanitize(sourceId));
        }
    }

    private void deleteScheduleForSource(String sourceId) {
        try {
            List<ScheduleConfiguration> schedules = scheduleStore.readAllSchedules(1000);
            for (ScheduleConfiguration schedule : schedules) {
                Map<String, Object> metadata = schedule.getMetadata();
                if (metadata != null && sourceId.equals(metadata.get("sourceId"))) {
                    scheduleStore.deleteSchedule(schedule.getId());
                    LOGGER.infof("Deleted schedule %s for ingestion source %s", LogSanitizer.sanitize(schedule.getId()),
                            LogSanitizer.sanitize(sourceId));
                    return;
                }
            }
        } catch (Exception e) {
            LOGGER.warnf(e, "Failed to delete schedule for source %s", LogSanitizer.sanitize(sourceId));
        }
    }

    private Instant computeNextFire(String cronExpression) {
        // Simple implementation: next fire is now + 1 minute (cron parsing is complex)
        // In production, this should use a proper cron parser
        return Instant.now().plusSeconds(60);
    }

    private String extractIdFromLocation(String location) {
        // Extract ID from location URI like:
        // http://host/ragstore/ingestion-sources/abc123
        // http://host/ragstore/ingestion-sources/abc123?version=1
        int lastSlash = location.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < location.length() - 1) {
            String idAndParams = location.substring(lastSlash + 1);
            // Strip query parameters (?version=1) and fragments
            int queryStart = idAndParams.indexOf('?');
            if (queryStart > 0) {
                return idAndParams.substring(0, queryStart);
            }
            int fragmentStart = idAndParams.indexOf('#');
            if (fragmentStart > 0) {
                return idAndParams.substring(0, fragmentStart);
            }
            return idAndParams;
        }
        return null;
    }
}
