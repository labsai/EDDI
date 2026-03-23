package ai.labs.eddi.configs.dictionary.expression;

import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration.WorkflowStep;
import ai.labs.eddi.configs.dictionary.IRestAction;
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

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestAction implements IRestAction {
    private final IWorkflowStore workflowStore;
    private final IRuleSetStore behaviorStore;
    private final IApiCallsStore httpCallsStore;
    private final IOutputStore outputStore;



    @Inject
    public RestAction(IWorkflowStore workflowStore,
                      IRuleSetStore behaviorStore,
                      IApiCallsStore httpCallsStore,
                      IOutputStore outputStore) {
        this.workflowStore = workflowStore;
        this.behaviorStore = behaviorStore;
        this.httpCallsStore = httpCallsStore;
        this.outputStore = outputStore;
    }

    @Override
    public List<String> readActions(String workflowId, Integer packageVersion, String filter, Integer limit) {
        List<String> retActions = new LinkedList<>();
        try {
            var workflowConfiguration = workflowStore.read(workflowId, packageVersion);

            List<String> actions;
            for (var workflowStep : workflowConfiguration.getWorkflowSteps()) {
                var type = workflowStep.getType().toString();
                var resourceId = extractUriFromConfig(workflowStep);
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

    private static IResourceStore.IResourceId extractUriFromConfig(WorkflowStep workflowStep) {
        var config = workflowStep.getConfig();
        var uri = URI.create(config.get("uri").toString());
        return RestUtilities.extractResourceId(uri);
    }
}
