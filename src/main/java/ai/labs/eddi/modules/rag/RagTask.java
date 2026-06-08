/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Map;

import static ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.ConfigValue;
import static ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.FieldType;

/**
 * Lifecycle task for RAG (Retrieval-Augmented Generation) configuration.
 * <p>
 * This task validates that the RAG configuration can be loaded. The actual
 * retrieval happens in {@link RagContextProvider} which is called from the LLM
 * task.
 * <p>
 * Workflow configuration:
 *
 * <pre>
 * {
 *   "type": "eddi://ai.labs.rag",
 *   "config": {
 *     "uri": "eddi://ai.labs.rag/ragstore/rags/{id}?version=1"
 *   }
 * }
 * </pre>
 *
 * @since 6.0.3
 */
@ApplicationScoped
public class RagTask implements ILifecycleTask {

    public static final String ID = "ai.labs.rag";
    public static final TaskId TASK_ID = new TaskId(ID);

    private static final Logger LOGGER = Logger.getLogger(RagTask.class);
    private static final String KEY_URI = "uri";

    private final IResourceClientLibrary resourceClientLibrary;

    @Inject
    public RagTask(IResourceClientLibrary resourceClientLibrary) {
        this.resourceClientLibrary = resourceClientLibrary;
    }

    @Override
    public TaskId getId() {
        return TASK_ID;
    }

    @Override
    public String getType() {
        return "eddi://ai.labs.rag";
    }

    @Override
    public void execute(IConversationMemory memory, Object component) throws LifecycleException {
        // The RagTask doesn't do anything during execution.
        // The actual RAG retrieval happens in RagContextProvider.retrieveContext()
        // which is called from the LlmTask.
        // This task exists only to validate the RAG configuration at workflow init
        // time.
        LOGGER.debug("RagTask execute called - RAG retrieval is handled by RagContextProvider in LlmTask");
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions) throws WorkflowConfigurationException {
        Object uriObj = configuration.get(KEY_URI);
        if (uriObj == null || uriObj.toString().isBlank()) {
            throw new WorkflowConfigurationException("No RAG configuration URI provided. Expected 'uri' field.");
        }

        String uriStr = uriObj.toString();
        try {
            URI uri = URI.create(uriStr);
            RagConfiguration ragConfig = resourceClientLibrary.getResource(uri, RagConfiguration.class);
            LOGGER.infof("RagTask configured with RAG config: %s (provider=%s, store=%s)",
                    ragConfig.getName(), ragConfig.getEmbeddingProvider(), ragConfig.getStoreType());
            return ragConfig;
        } catch (ServiceException e) {
            LOGGER.errorf(e, "Failed to load RAG configuration from URI: %s", uriStr);
            throw new WorkflowConfigurationException("Failed to load RAG configuration: " + e.getMessage(), e);
        }
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor descriptor = new ExtensionDescriptor(TASK_ID);
        descriptor.setDisplayName("RAG Knowledge Base");

        ConfigValue uriConfig = new ConfigValue("RAG Configuration URI", FieldType.URI, false, null);
        descriptor.getConfigs().put(KEY_URI, uriConfig);

        return descriptor;
    }
}
