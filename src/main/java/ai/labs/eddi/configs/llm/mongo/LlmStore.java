/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.mongo;

import ai.labs.eddi.configs.hitl.HitlConfigValidation;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * @author ginccc
 */
@ApplicationScoped
public class LlmStore extends AbstractResourceStore<LlmConfiguration> implements ILlmStore {

    @Inject
    public LlmStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, "llms", documentBuilder, LlmConfiguration.class);
    }

    @Override
    public IResourceStore.IResourceId create(LlmConfiguration content) throws IResourceStore.ResourceStoreException {
        validateTaskToolApprovals(content);
        return super.create(content);
    }

    @Override
    @IResourceStore.ConfigurationUpdate
    public Integer update(String id, Integer version, LlmConfiguration content)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        validateTaskToolApprovals(content);
        return super.update(id, version, content);
    }

    /**
     * Validates the per-task {@code toolApprovals} override on every LLM task at
     * save time (tool-level HITL). Mirrors the agent-level validation seam.
     */
    private static void validateTaskToolApprovals(LlmConfiguration content) {
        if (content == null || content.tasks() == null) {
            return;
        }
        for (int i = 0; i < content.tasks().size(); i++) {
            LlmConfiguration.Task task = content.tasks().get(i);
            if (task != null) {
                HitlConfigValidation.validateToolApprovals(task.getToolApprovals(), "langchain.task[" + i + "].toolApprovals");
            }
        }
    }
}
