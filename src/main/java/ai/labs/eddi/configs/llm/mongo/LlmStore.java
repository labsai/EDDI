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
import ai.labs.eddi.engine.hitl.tools.TaskToolApprovalsResolver;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * @author ginccc
 */
@ApplicationScoped
public class LlmStore extends AbstractResourceStore<LlmConfiguration> implements ILlmStore {

    private static final Logger LOGGER = Logger.getLogger(LlmStore.class);

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
                String fieldPath = "langchain.task[" + i + "].toolApprovals";
                HitlConfigValidation.validateToolApprovals(task.getToolApprovals(), fieldPath);
                warnStrictModeImplications(task.getToolApprovals(), fieldPath);
            }
        }
    }

    /**
     * Save-time visibility for settings the STRICT combination mode (the default of
     * {@code eddi.hitl.tool.task-approvals.mode}) will not honour as written. A
     * warning, not a rejection: the config is legal, {@code replace} mode honours
     * it fully, and an already-stored document must never brick — but an author who
     * expects a task-level exemption or AUTO_APPROVE to take effect should learn at
     * save time, not from a pause that never lifts.
     */
    private static void warnStrictModeImplications(ToolApprovalsConfig cfg, String fieldPath) {
        if (cfg == null || TaskToolApprovalsResolver.configuredMode() != TaskToolApprovalsResolver.Mode.STRICT) {
            // In `replace` mode these settings ARE honoured verbatim — warning that
            // they are ignored would be flatly wrong, and a warning authors learn to
            // disregard is worse than none.
            return;
        }
        if (cfg.getExempt() != null && !cfg.getExempt().isEmpty()) {
            LOGGER.warnf("%s.exempt is IGNORED under eddi.hitl.tool.task-approvals.mode=strict: "
                    + "task-level exemptions cannot loosen the agent-level gate. Set the mode to 'replace' if this "
                    + "task is deliberately looser than its agent.", fieldPath);
        }
        if (cfg.getTimeoutPolicy() == HitlTimeoutPolicy.AUTO_APPROVE) {
            LOGGER.warnf("%s.timeoutPolicy=AUTO_APPROVE is DEMOTED to WAIT_INDEFINITELY under "
                    + "eddi.hitl.tool.task-approvals.mode=strict unless the agent-level policy is itself "
                    + "AUTO_APPROVE.", fieldPath);
        }
        if (cfg.getRules() != null) {
            for (var rule : cfg.getRules()) {
                if (rule != null && rule.getTimeoutPolicy() == HitlTimeoutPolicy.AUTO_APPROVE) {
                    LOGGER.warnf("%s.rules[match='%s'].timeoutPolicy=AUTO_APPROVE is DEMOTED to WAIT_INDEFINITELY "
                            + "under eddi.hitl.tool.task-approvals.mode=strict.", fieldPath, rule.getMatch());
                }
            }
        }
    }
}
