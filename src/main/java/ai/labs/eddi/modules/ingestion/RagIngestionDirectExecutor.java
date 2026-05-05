/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.ingestion.IRagIngestionSourceStore;
import ai.labs.eddi.configs.ingestion.model.RagIngestionSource;
import ai.labs.eddi.engine.schedule.DirectScheduleExecutor;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Direct schedule executor for RAG ingestion.
 * <p>
 * When an ingestion source creates a schedule with
 * {@code directExecutionType = "ingestion"}, this executor runs the ingestion
 * pipeline directly without needing an agent.
 * <p>
 * The schedule metadata must contain a valid {@code sourceId} — the ingestion
 * source's MongoDB ObjectId (24-character hex string).
 *
 * @since 6.0.3
 */
@ApplicationScoped
public class RagIngestionDirectExecutor implements DirectScheduleExecutor {

    private static final Logger LOGGER = Logger.getLogger(RagIngestionDirectExecutor.class);

    private static final String OBJECTID_PATTERN = "^[0-9a-fA-F]{24}$";

    private final RagIngestionService ingestionService;
    private final IRagIngestionSourceStore sourceStore;

    @Inject
    public RagIngestionDirectExecutor(RagIngestionService ingestionService,
            IRagIngestionSourceStore sourceStore) {
        this.ingestionService = ingestionService;
        this.sourceStore = sourceStore;
    }

    @Override
    public String getType() {
        return "ingestion";
    }

    @Override
    public void execute(ScheduleConfiguration schedule) throws Exception {
        var metadata = schedule.getMetadata();
        if (metadata == null) {
            throw new IllegalArgumentException("Schedule metadata is null — expected 'sourceId'");
        }

        String sourceId = (String) metadata.get("sourceId");
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException(
                    "Schedule metadata missing required field: 'sourceId'");
        }

        // Strip legacy query parameters from sourceId (e.g., "abc123?version=1" →
        // "abc123")
        sourceId = stripIdParams(sourceId);

        // Validate sourceId is a valid MongoDB ObjectId (24-char hex)
        if (!sourceId.matches(OBJECTID_PATTERN)) {
            throw new IllegalArgumentException(
                    "Schedule metadata field 'sourceId' is not a valid MongoDB ObjectId "
                            + "(expected 24-character hex string, got '" + sourceId + "' of length "
                            + sourceId.length() + "). "
                            + "This schedule may have been created with an older version of EDDI. "
                            + "Delete this schedule and re-create the ingestion source to fix it.");
        }

        LOGGER.infof("[INGESTION SCHEDULE] Executing scheduled ingestion for source '%s'", sourceId);

        // Load the latest version of the ingestion source config
        var resourceId = sourceStore.getCurrentResourceId(sourceId);
        RagIngestionSource sourceConfig = sourceStore.read(resourceId.getId(), resourceId.getVersion());

        // Run the ingestion pipeline
        RagIngestionService.IngestionResult result = ingestionService.ingest(sourceId, sourceConfig);

        // Log results
        if (result.isSuccess()) {
            LOGGER.infof("[INGESTION SCHEDULE] Completed for source '%s': %d documents, %d chunks in %d ms",
                    sourceId, result.documentsProcessed(), result.chunksStored(), result.durationMs());
        } else {
            LOGGER.errorf("[INGESTION SCHEDULE] Failed for source '%s': %s",
                    sourceId, result.error());
        }
    }

    /**
     * Strips query parameters and fragments from a legacy sourceId string.
     * <p>
     * Older versions of EDDI stored sourceIds with {@code ?version=1} appended
     * (e.g., {@code "69f8d53ec862da322d8940c0?version=1"}). This method cleans them
     * up to ensure backward compatibility with existing schedules.
     *
     * @param rawId
     *            the raw sourceId from schedule metadata
     * @return the clean sourceId without query parameters or fragments
     */
    private static String stripIdParams(String rawId) {
        if (rawId == null)
            return null;
        int queryStart = rawId.indexOf('?');
        if (queryStart > 0) {
            return rawId.substring(0, queryStart);
        }
        int fragmentStart = rawId.indexOf('#');
        if (fragmentStart > 0) {
            return rawId.substring(0, fragmentStart);
        }
        return rawId;
    }
}
