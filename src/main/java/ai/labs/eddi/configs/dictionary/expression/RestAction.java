/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.dictionary.expression;

import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration.WorkflowStep;
import ai.labs.eddi.configs.dictionary.IRestAction;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.utils.CollectionUtilities;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestAction implements IRestAction {
    private final IWorkflowStore workflowStore;
    private final IRuleSetStore behaviorStore;
    private final IApiCallsStore httpCallsStore;
    private final IOutputStore outputStore;
    private final ResourceAccessGuard accessGuard;

    @Inject
    public RestAction(IWorkflowStore workflowStore, IRuleSetStore behaviorStore, IApiCallsStore httpCallsStore, IOutputStore outputStore,
            ResourceAccessGuard accessGuard) {
        this.accessGuard = accessGuard;
        this.workflowStore = workflowStore;
        this.behaviorStore = behaviorStore;
        this.httpCallsStore = httpCallsStore;
        this.outputStore = outputStore;
    }

    @Override
    public List<String> readActions(String workflowId, Integer workflowVersion, String filter, Integer limit) {
        List<String> retActions = new LinkedList<>();
        // This fans out from one workflow into whichever rule sets, api calls, output
        // sets and dictionaries it references, reading those stores directly. The
        // workflow is the entry point the caller named, so it is what access is
        // decided against — without this the helper reads any workflow, unguarded.
        if (workflowId == null || workflowId.isBlank()) {
            throw new BadRequestException("workflowId is required");
        }
        accessGuard.requireAccess(workflowId, AccessLevel.VIEW, "workflow");
        try {
            var workflowConfiguration = workflowStore.read(workflowId, workflowVersion);

            List<String> actions;
            for (var workflowStep : workflowConfiguration.getWorkflowSteps()) {
                // Parser and templating steps carry no resource URI, and every real
                // workflow starts with the parser — reading config.get("uri") blindly
                // turned the whole request into a 500 for any ordinary agent.
                var resourceId = extractUriFromConfig(workflowStep);
                if (resourceId == null) {
                    continue;
                }
                var type = workflowStep.getType().toString();
                var id = resourceId.getId();
                var version = resourceId.getVersion();

                if (type.startsWith("eddi://ai.labs.rules")) {
                    actions = behaviorStore.readActions(id, version, filter, limit);
                } else if (type.startsWith("eddi://ai.labs.apicalls")) {
                    actions = httpCallsStore.readActions(id, version, filter, limit);
                } else if (type.startsWith("eddi://ai.labs.output")) {
                    actions = outputStore.readActions(id, version, filter, limit);
                } else {
                    actions = Collections.emptyList();
                }

                CollectionUtilities.addAllWithoutDuplicates(retActions, actions);
            }

            return retActions;
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    /**
     * The step's resource id, or {@code null} for a step without a resource URI.
     */
    private static IResourceStore.IResourceId extractUriFromConfig(WorkflowStep workflowStep) {
        var config = workflowStep.getConfig();
        Object uri = config != null ? config.get("uri") : null;
        if (workflowStep.getType() == null || uri == null || uri.toString().isBlank()) {
            return null;
        }
        return RestUtilities.extractResourceId(URI.create(uri.toString()));
    }
}
