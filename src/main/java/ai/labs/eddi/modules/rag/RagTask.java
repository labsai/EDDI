/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Map;

import static ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.ConfigValue;
import static ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.FieldType;

/**
 * Carries a knowledge-base binding into a workflow. A workflow step of type
 * {@code eddi://ai.labs.rag} declares which {@link RagConfiguration} an agent
 * retrieves from:
 *
 * <pre>
 * {
 *   "type": "eddi://ai.labs.rag",
 *   "config": { "uri": "eddi://ai.labs.rag/ragstore/rags/{id}?version=1" }
 * }
 * </pre>
 *
 * <h2>Why this task does nothing at execution time</h2>
 * <p>
 * Retrieval is deliberately NOT a pipeline step. It happens inside the LLM
 * task, where the user's query is already known and the retrieved passages can
 * be folded straight into the prompt — see
 * {@link ai.labs.eddi.modules.llm.impl.RagContextProvider}, which discovers
 * these steps by reading the workflow document rather than by being handed this
 * task's component. That decision (Phase 8c) stands.
 * <p>
 * The step still has to <em>exist</em>, though. {@code
 * WorkflowStoreClientLibrary} rejects any workflow step whose type is not a
 * registered lifecycle extension, so without this task — and the
 * {@link ai.labs.eddi.modules.rag.bootstrap.RagModule} that registers it — a
 * workflow containing a RAG step cannot be deployed at all, and the Manager's
 * step chooser never offers one. This task is that registration: it validates
 * the binding when the workflow is loaded and then stays out of the way.
 *
 * @see ai.labs.eddi.modules.llm.impl.RagContextProvider
 */
@ApplicationScoped
public class RagTask implements ILifecycleTask {

    public static final String ID = "ai.labs.rag";
    public static final TaskId TASK_ID = new TaskId(ID);

    /**
     * Lifecycle stage identifier. Per {@link ILifecycleTask#getType()} this is a
     * stage name ("output", "httpcalls", …), NOT the {@code eddi://} step URI — the
     * URI form would not match any stage filter used for partial lifecycle
     * execution.
     */
    static final String KEY_RAG = "rag";

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
        return KEY_RAG;
    }

    /**
     * No-op by design — see the class Javadoc. Retrieval runs in the LLM task.
     */
    @Override
    public void execute(IConversationMemory memory, Object component) {
        // Intentionally empty. RagContextProvider performs retrieval during the
        // LLM task; this task exists so the workflow step can be declared and
        // validated. Do not add retrieval here without reading the class Javadoc.
    }

    /**
     * Resolves the referenced knowledge base so a broken binding fails when the
     * workflow is deployed rather than silently returning no context on the first
     * conversation.
     */
    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
            throws WorkflowConfigurationException {

        Object uriObj = configuration == null ? null : configuration.get(KEY_URI);
        if (uriObj == null || uriObj.toString().isBlank()) {
            throw new WorkflowConfigurationException(
                    "No knowledge base URI defined! Expected config field '" + KEY_URI
                            + "', e.g. eddi://ai.labs.rag/ragstore/rags/{id}?version=1");
        }

        String uriString = uriObj.toString();
        URI uri;
        try {
            uri = URI.create(uriString);
        } catch (IllegalArgumentException e) {
            throw new WorkflowConfigurationException(
                    "Knowledge base URI is not a valid URI: " + uriString, e);
        }

        try {
            RagConfiguration ragConfig = resourceClientLibrary.getResource(uri, RagConfiguration.class);
            LOGGER.debugf("Bound knowledge base '%s' (provider=%s, store=%s)",
                    LogSanitizer.sanitize(ragConfig.getName()),
                    LogSanitizer.sanitize(String.valueOf(ragConfig.getEmbeddingProvider())),
                    LogSanitizer.sanitize(String.valueOf(ragConfig.getStoreType())));
            return ragConfig;
        } catch (ServiceException e) {
            throw new WorkflowConfigurationException(
                    "Failed to load knowledge base from URI: " + uriString + " (" + e.getMessage() + ")", e);
        }
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor descriptor = new ExtensionDescriptor(TASK_ID);
        descriptor.setDisplayName("Knowledge Base (RAG)");
        descriptor.getConfigs().put(KEY_URI, new ConfigValue("Knowledge Base URI", FieldType.URI, false, null));
        return descriptor;
    }
}
