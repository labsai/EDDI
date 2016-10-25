package io.sls.persistence.impl.output.rest.keys;

import io.sls.persistence.IResourceStore;
import io.sls.resources.rest.behavior.IBehaviorStore;
import io.sls.resources.rest.behavior.model.BehaviorConfiguration;
import io.sls.resources.rest.behavior.model.BehaviorGroupConfiguration;
import io.sls.resources.rest.behavior.model.BehaviorRuleConfiguration;
import io.sls.resources.rest.output.IOutputStore;
import io.sls.resources.rest.output.keys.IRestOutputKeys;
import io.sls.resources.rest.packages.IPackageStore;
import io.sls.resources.rest.packages.model.PackageConfiguration;
import io.sls.utilities.CollectionUtilities;
import io.sls.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.*;

/**
 * User: jarisch
 * Date: 26.11.12
 * Time: 16:20
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
                List<String> outputKeys = outputStore.readOutputKeys(resourceId.getId(), resourceId.getVersion(), filter, "asc", limit);
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
            if (!packageExtension.getType().toString().startsWith("core://io.sls.behavior")) {
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
            if (!packageExtension.getType().toString().startsWith("core://io.sls.output")) {
                continue;
            }

            Map<String, Object> config = packageExtension.getConfig();
            String uri = (String) config.get("uri");
            resourceIds.add(RestUtilities.extractResourceId(URI.create(uri)));
        }

        return resourceIds;
    }
}

