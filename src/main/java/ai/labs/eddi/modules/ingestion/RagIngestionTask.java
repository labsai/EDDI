/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.ingestion.model.RagIngestionSource;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.ConfigValue;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.FieldType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Lifecycle task for RAG ingestion.
 * <p>
 * This task is triggered as part of an ingestion agent workflow. It:
 * <ol>
 * <li>Reads the ingestion source configuration from the workflow URI</li>
 * <li>Calls {@link RagIngestionService} to fetch, convert, and ingest documents
 * from any supported source type (web, file, Git, etc.)</li>
 * <li>Stores the {@link RagIngestionService.IngestionResult} in conversation
 * memory</li>
 * </ol>
 * <p>
 * Configuration:
 *
 * <pre>
 * {
 *   "uri": "eddi://ai.labs.ingestion/ingestionstore/ingestionsources/{id}?version=1"
 * }
 * </pre>
 *
 * @since 6.0.3
 */
@ApplicationScoped
public class RagIngestionTask implements ILifecycleTask {

    public static final String ID = "ai.labs.ingestion";
    public static final TaskId TASK_ID = new TaskId(ID);

    private static final Logger LOGGER = Logger.getLogger(RagIngestionTask.class);
    public static final String KEY_INGESTION_RESULT = "ingestion:result";
    public static final String KEY_INGESTION_SOURCE = "ingestion:source";

    private final RagIngestionService ingestionService;
    private final IResourceClientLibrary resourceClientLibrary;
    private final IDataFactory dataFactory;

    @Inject
    public RagIngestionTask(RagIngestionService ingestionService,
            IResourceClientLibrary resourceClientLibrary,
            IDataFactory dataFactory) {
        this.ingestionService = ingestionService;
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
    }

    @Override
    public TaskId getId() {
        return TASK_ID;
    }

    @Override
    public String getType() {
        return "ingestion";
    }

    @Override
    public void execute(IConversationMemory memory, Object component) throws LifecycleException {
        if (!(component instanceof IngestionTaskConfig config)) {
            throw new LifecycleException("Expected IngestionTaskConfig but got: " +
                    (component != null ? component.getClass().getName() : "null"));
        }

        String sourceId = config.sourceId();
        RagIngestionSource sourceConfig = config.sourceConfig();

        LOGGER.infof("[INGESTION TASK] Executing ingestion for source: %s (type=%s, name=%s)",
                sanitize(sourceId), sanitize(sourceConfig.type()), sanitize(sourceConfig.name()));

        // Run ingestion (blocking call - runs on virtual thread if needed)
        RagIngestionService.IngestionResult result = ingestionService.ingest(sourceId, sourceConfig);

        // Build source info for memory storage
        Map<String, Object> sourceInfo = new HashMap<>();
        sourceInfo.put("id", sourceId);
        sourceInfo.put("name", sourceConfig.name());
        sourceInfo.put("type", sourceConfig.type());

        // Add type-specific identifier (startUrl for web, path for file, etc.)
        if ("web".equals(sourceConfig.type())) {
            try {
                sourceInfo.put("sourceIdentifier", sourceConfig.webConfig().startUrl());
            } catch (Exception e) {
                LOGGER.debugf("Could not extract web startUrl for source %s", sanitize(sourceId));
            }
        }

        // Store result in conversation memory
        var resultData = dataFactory.createData(KEY_INGESTION_RESULT, result, true);
        var sourceData = dataFactory.createData(KEY_INGESTION_SOURCE, sourceInfo, true);

        memory.getCurrentStep().storeData(resultData);
        memory.getCurrentStep().storeData(sourceData);

        // Add conversation output
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("type", "ingestion_result");
        output.put("sourceId", sourceId);
        output.put("sourceName", sourceConfig.name());
        output.put("sourceType", sourceConfig.type());
        output.put("success", result.isSuccess());
        output.put("documentsProcessed", result.documentsProcessed());
        output.put("documentsNew", result.documentsNew());
        output.put("documentsUnchanged", result.documentsUnchanged());
        output.put("documentsStale", result.documentsStale());
        output.put("chunksStored", result.chunksStored());
        output.put("errors", result.errors());
        output.put("durationMs", result.durationMs());

        if (result.error().isPresent()) {
            output.put("error", result.error().get());
        }

        memory.getCurrentStep().addConversationOutputObject("ingestion", output);

        LOGGER.infof("[INGESTION TASK] Completed for source: %s, success=%s, documents=%d, chunks=%d",
                sanitize(sourceId), result.isSuccess(), result.documentsProcessed(), result.chunksStored());
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
            throws WorkflowConfigurationException {

        Object uriObj = configuration.get("uri");
        if (uriObj == null || uriObj.toString().isBlank()) {
            throw new WorkflowConfigurationException("No ingestion source URI configured. Expected 'uri' field.");
        }

        String uriStr = uriObj.toString();

        try {
            URI uri = URI.create(uriStr);
            RagIngestionSource sourceConfig = resourceClientLibrary.getResource(uri, RagIngestionSource.class);

            // Extract source ID from URI path
            String sourceId = extractSourceId(uriStr);

            LOGGER.infof("[INGESTION TASK] Configured with source: %s (type=%s, name=%s)",
                    sanitize(sourceId), sanitize(sourceConfig.type()), sanitize(sourceConfig.name()));

            return new IngestionTaskConfig(sourceId, sourceConfig);

        } catch (Exception e) {
            throw new WorkflowConfigurationException(
                    "Failed to load ingestion source from URI: " + uriStr + " - " + e.getMessage(), e);
        }
    }

    private String extractSourceId(String uriStr) {
        // Extract ID from URI like:
        // eddi://ai.labs.ingestion/ingestionstore/ingestionsources/{id}?version=1
        try {
            int lastSlash = uriStr.lastIndexOf('/');
            int queryStart = uriStr.indexOf('?', lastSlash);
            if (queryStart > lastSlash) {
                return uriStr.substring(lastSlash + 1, queryStart);
            } else if (lastSlash > 0) {
                return uriStr.substring(lastSlash + 1);
            }
        } catch (Exception e) {
            LOGGER.warnf("Could not extract source ID from URI: %s", sanitize(uriStr));
        }
        return "unknown";
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor descriptor = new ExtensionDescriptor(TASK_ID);
        descriptor.setDisplayName("RAG Ingestion");

        // URI config for the ingestion source
        var uriConfig = new ConfigValue(
                "Ingestion Source URI",
                FieldType.URI,
                false,
                null);
        descriptor.getConfigs().put("uri", uriConfig);

        return descriptor;
    }

    /**
     * Configuration object for the ingestion task.
     */
    public record IngestionTaskConfig(String sourceId, RagIngestionSource sourceConfig) {
    }
}
