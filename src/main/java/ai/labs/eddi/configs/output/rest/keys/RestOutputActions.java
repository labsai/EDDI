package ai.labs.eddi.configs.output.rest.keys;

import ai.labs.eddi.configs.rules.IBehaviorStore;
import ai.labs.eddi.configs.rules.model.BehaviorConfiguration;
import ai.labs.eddi.configs.rules.model.BehaviorGroupConfiguration;
import ai.labs.eddi.configs.rules.model.BehaviorRuleConfiguration;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.output.keys.IRestOutputActions;
import ai.labs.eddi.configs.pipelines.IPipelineStore;
import ai.labs.eddi.configs.pipelines.model.PipelineConfiguration;
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
    private final IPipelineStore PipelineStore;
    private final IBehaviorStore behaviorStore;
    private final IOutputStore outputStore;



    @Inject
    public RestOutputActions(IPipelineStore PipelineStore,
                             IBehaviorStore behaviorStore,
                             IOutputStore outputStore) {
        this.PipelineStore = PipelineStore;
        this.behaviorStore = behaviorStore;
        this.outputStore = outputStore;
    }

    @Override
    public List<String> readOutputActions(String packageId, Integer packageVersion, String filter, Integer limit) {
        List<String> retOutputKeys = new LinkedList<>();
        try {
            PipelineConfiguration PipelineConfiguration = PipelineStore.read(packageId, packageVersion);
            List<IResourceStore.IResourceId> resourceIds;
            resourceIds = readBehaviorRuleSetResourceIds(PipelineConfiguration);
            for (IResourceStore.IResourceId resourceId : resourceIds) {
                BehaviorConfiguration behaviorConfiguration = behaviorStore.read(resourceId.getId(), resourceId.getVersion());
                for (BehaviorGroupConfiguration groupConfiguration : behaviorConfiguration.getBehaviorGroups()) {
                    for (BehaviorRuleConfiguration behaviorRuleConfiguration : groupConfiguration.getBehaviorRules()) {
                        for (String action : behaviorRuleConfiguration.getActions()) {
                            if (action.contains(filter)) {
                                CollectionUtilities.addAllWithoutDuplicates(retOutputKeys, List.of(action));
                                if (retOutputKeys.size() >= limit) {
                                    return sortedOutputKeys(retOutputKeys);
                                }
                            }
                        }
                    }
                }
            }

            resourceIds = readOutputSetResourceIds(PipelineConfiguration);
            for (IResourceStore.IResourceId resourceId : resourceIds) {
                List<String> outputKeys = outputStore.readActions(resourceId.getId(), resourceId.getVersion(), filter, limit);
                CollectionUtilities.addAllWithoutDuplicates(retOutputKeys, outputKeys);
                if (retOutputKeys.size() >= limit) {
                    return sortedOutputKeys(retOutputKeys);
                }
            }

            return sortedOutputKeys(retOutputKeys);
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

    private List<IResourceStore.IResourceId> readBehaviorRuleSetResourceIds(PipelineConfiguration PipelineConfiguration) {
        List<IResourceStore.IResourceId> resourceIds = new LinkedList<>();

        for (PipelineConfiguration.PipelineStep PipelineStep : PipelineConfiguration.getPipelineSteps()) {
            if (!PipelineStep.getType().toString().startsWith("eddi://ai.labs.behavior")) {
                continue;
            }

            Map<String, Object> config = PipelineStep.getConfig();
            String uri = (String) config.get("uri");
            resourceIds.add(RestUtilities.extractResourceId(URI.create(uri)));
        }

        return resourceIds;

    }

    private List<IResourceStore.IResourceId> readOutputSetResourceIds(PipelineConfiguration PipelineConfiguration) {
        List<IResourceStore.IResourceId> resourceIds = new LinkedList<>();

        for (PipelineConfiguration.PipelineStep PipelineStep : PipelineConfiguration.getPipelineSteps()) {
            if (!PipelineStep.getType().toString().startsWith("eddi://ai.labs.output")) {
                continue;
            }

            Map<String, Object> config = PipelineStep.getConfig();
            String uri = (String) config.get("uri");
            resourceIds.add(RestUtilities.extractResourceId(URI.create(uri)));
        }

        return resourceIds;
    }
}

