package ai.labs.resources.impl.config.output.rest.keys;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.config.behavior.IBehaviorStore;
import ai.labs.resources.rest.config.behavior.model.BehaviorConfiguration;
import ai.labs.resources.rest.config.behavior.model.BehaviorGroupConfiguration;
import ai.labs.resources.rest.config.behavior.model.BehaviorRuleConfiguration;
import ai.labs.resources.rest.config.output.IOutputStore;
import ai.labs.resources.rest.config.output.keys.IRestOutputKeys;
import ai.labs.resources.rest.config.packages.IPackageStore;
import ai.labs.resources.rest.config.packages.model.PackageConfiguration;
import ai.labs.utilities.CollectionUtilities;
import ai.labs.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@Slf4j
public class RestOutputKeys implements IRestOutputKeys {
    private final IPackageStore packageStore;
    private final IBehaviorStore behaviorStore;
    private final IOutputStore outputStore;

    @Inject
    public RestOutputKeys(IPackageStore packageStore,
                          IBehaviorStore behaviorStore,
                          IOutputStore outputStore) {
        this.packageStore = packageStore;
        this.behaviorStore = behaviorStore;
        this.outputStore = outputStore;
    }
    
    @Override
    public List<String> readOutputKeys(String packageId, Integer packageVersion, String filter, Integer limit) {
        List<String> retOutputKeys = new LinkedList<String>();
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
                                CollectionUtilities.addAllWithoutDuplicates(retOutputKeys, Arrays.asList(action));
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
                List<String> outputKeys = outputStore.readOutputActions(resourceId.getId(), resourceId.getVersion(), filter, "asc", limit);
                CollectionUtilities.addAllWithoutDuplicates(retOutputKeys, outputKeys);
                if (retOutputKeys.size() >= limit) {
                    return sortedOutputKeys(retOutputKeys);
                }
            }

            return sortedOutputKeys(retOutputKeys);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
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
        List<IResourceStore.IResourceId> resourceIds = new LinkedList<IResourceStore.IResourceId>();

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
        List<IResourceStore.IResourceId> resourceIds = new LinkedList<IResourceStore.IResourceId>();

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

