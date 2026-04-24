/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.output.rest.keys;

import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.rules.model.RuleGroupConfiguration;
import ai.labs.eddi.configs.rules.model.RuleConfiguration;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.output.keys.IRestOutputActions;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.utils.CollectionUtilities;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestOutputActions implements IRestOutputActions {
    private final IWorkflowStore workflowStore;
    private final IRuleSetStore behaviorStore;
    private final IOutputStore outputStore;

    @Inject
    public RestOutputActions(IWorkflowStore workflowStore, IRuleSetStore behaviorStore, IOutputStore outputStore) {
        this.workflowStore = workflowStore;
        this.behaviorStore = behaviorStore;
        this.outputStore = outputStore;
    }

    @Override
    public List<String> readOutputActions(String workflowId, Integer workflowVersion, String filter, Integer limit) {
        List<String> retOutputKeys = new LinkedList<>();
        try {
            WorkflowConfiguration workflowConfig = workflowStore.read(workflowId, workflowVersion);
            List<IResourceStore.IResourceId> resourceIds;
            resourceIds = readRuleSetResourceIds(workflowConfig);
            for (IResourceStore.IResourceId resourceId : resourceIds) {
                RuleSetConfiguration behaviorConfiguration = behaviorStore.read(resourceId.getId(), resourceId.getVersion());
                for (RuleGroupConfiguration groupConfiguration : behaviorConfiguration.getBehaviorGroups()) {
                    for (RuleConfiguration behaviorRuleConfiguration : groupConfiguration.getRules()) {
                        for (String action : behaviorRuleConfiguration.getActions()) {
                            if (action.contains(filter)) {
                                CollectionUtilities.addAllWithoutDuplicates(retOutputKeys, List.of(action));
                            }
                        }
                    }
                }
            }

            resourceIds = readOutputSetResourceIds(workflowConfig);
            for (IResourceStore.IResourceId resourceId : resourceIds) {
                List<String> outputKeys = outputStore.readActions(resourceId.getId(), resourceId.getVersion(), filter, limit);
                CollectionUtilities.addAllWithoutDuplicates(retOutputKeys, outputKeys);
            }

            // Sort the full aggregated list first, then truncate to the requested limit.
            // This ensures deterministic, alphabetically-first results regardless of
            // insertion order.
            List<String> sorted = sortedOutputKeys(retOutputKeys);
            return sorted.size() > limit ? sorted.subList(0, limit) : sorted;
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    private List<String> sortedOutputKeys(List<String> retOutputKeys) {
        Collections.sort(retOutputKeys);
        return retOutputKeys;
    }

    private List<IResourceStore.IResourceId> readRuleSetResourceIds(WorkflowConfiguration workflowConfiguration) {
        List<IResourceStore.IResourceId> resourceIds = new LinkedList<>();

        for (WorkflowConfiguration.WorkflowStep workflowStep : workflowConfiguration.getWorkflowSteps()) {
            if (!workflowStep.getType().toString().startsWith("eddi://ai.labs.rules")) {
                continue;
            }

            Map<String, Object> config = workflowStep.getConfig();
            String uri = (String) config.get("uri");
            resourceIds.add(RestUtilities.extractResourceId(URI.create(uri)));
        }

        return resourceIds;

    }

    private List<IResourceStore.IResourceId> readOutputSetResourceIds(WorkflowConfiguration workflowConfiguration) {
        List<IResourceStore.IResourceId> resourceIds = new LinkedList<>();

        for (WorkflowConfiguration.WorkflowStep workflowStep : workflowConfiguration.getWorkflowSteps()) {
            if (!workflowStep.getType().toString().startsWith("eddi://ai.labs.output")) {
                continue;
            }

            Map<String, Object> config = workflowStep.getConfig();
            String uri = (String) config.get("uri");
            resourceIds.add(RestUtilities.extractResourceId(URI.create(uri)));
        }

        return resourceIds;
    }
}
