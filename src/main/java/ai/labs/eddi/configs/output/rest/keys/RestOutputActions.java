package ai.labs.eddi.configs.output.rest.keys;

import ai.labs.eddi.configs.behavior.IBehaviorStore;
import ai.labs.eddi.configs.behavior.model.BehaviorConfiguration;
import ai.labs.eddi.configs.behavior.model.BehaviorGroupConfiguration;
import ai.labs.eddi.configs.behavior.model.BehaviorRuleConfiguration;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.output.keys.IRestOutputActions;
import ai.labs.eddi.configs.packages.IPackageStore;
import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.utils.CollectionUtilities;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import org.jboss.logging.Logger;

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
    private final IPackageStore packageStore;
    private final IBehaviorStore behaviorStore;
    private final IOutputStore outputStore;

    private static final Logger log = Logger.getLogger(RestOutputActions.class);

    @Inject
    public RestOutputActions(IPackageStore packageStore,
                             IBehaviorStore behaviorStore,
                             IOutputStore outputStore) {
        this.packageStore = packageStore;
        this.behaviorStore = behaviorStore;
        this.outputStore = outputStore;
    }

    @Override
    public List<String> readOutputActions(String packageId, Integer packageVersion, String filter, Integer limit) {
        List<String> retOutputKeys = new LinkedList<>();
        try {
            PackageConfiguration packageConfiguration = packageStore.read(packageId, packageVersion);
            List<IResourceStore.IResourceId> resourceIds;
            resourceIds = readBehaviorRuleSetResourceIds(packageConfiguration);
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

            resourceIds = readOutputSetResourceIds(packageConfiguration);
            for (IResourceStore.IResourceId resourceId : resourceIds) {
                List<String> outputKeys = outputStore.readActions(resourceId.getId(), resourceId.getVersion(), filter, limit);
                CollectionUtilities.addAllWithoutDuplicates(retOutputKeys, outputKeys);
                if (retOutputKeys.size() >= limit) {
                    return sortedOutputKeys(retOutputKeys);
                }
            }

            return sortedOutputKeys(retOutputKeys);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e);
        }
    }

    private List<String> sortedOutputKeys(List<String> retOutputKeys) {
        Collections.sort(retOutputKeys);
        return retOutputKeys;
    }

    private List<IResourceStore.IResourceId> readBehaviorRuleSetResourceIds(PackageConfiguration packageConfiguration) {
        List<IResourceStore.IResourceId> resourceIds = new LinkedList<>();

        for (PackageConfiguration.PackageExtension packageExtension : packageConfiguration.getPackageExtensions()) {
            if (!packageExtension.getType().toString().startsWith("eddi://ai.labs.behavior")) {
                continue;
            }

            Map<String, Object> config = packageExtension.getConfig();
            String uri = (String) config.get("uri");
            resourceIds.add(RestUtilities.extractResourceId(URI.create(uri)));
        }

        return resourceIds;

    }

    private List<IResourceStore.IResourceId> readOutputSetResourceIds(PackageConfiguration packageConfiguration) {
        List<IResourceStore.IResourceId> resourceIds = new LinkedList<>();

        for (PackageConfiguration.PackageExtension packageExtension : packageConfiguration.getPackageExtensions()) {
            if (!packageExtension.getType().toString().startsWith("eddi://ai.labs.output")) {
                continue;
            }

            Map<String, Object> config = packageExtension.getConfig();
            String uri = (String) config.get("uri");
            resourceIds.add(RestUtilities.extractResourceId(URI.create(uri)));
        }

        return resourceIds;
    }
}

