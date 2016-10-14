package io.sls.persistence.impl.expression;

import io.sls.persistence.IResourceStore;
import io.sls.resources.rest.expression.IRestExpressions;
import io.sls.resources.rest.packages.IPackageStore;
import io.sls.resources.rest.packages.model.PackageConfiguration;
import io.sls.resources.rest.regulardictionary.IRegularDictionaryStore;
import io.sls.utilities.CollectionUtilities;
import io.sls.utilities.RestUtilities;
import org.jboss.resteasy.spi.NoLogWebApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Copyright by Spoken Language System. All rights reserved.
 * User: jarisch
 * Date: 26.11.12
 * Time: 14:59
 */
public class RestExpression implements IRestExpressions {
    private final IPackageStore packageStore;
    private final IRegularDictionaryStore regularDictionaryStore;
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Inject
    public RestExpression(IPackageStore packageStore,
                          IRegularDictionaryStore regularDictionaryStore) {
        this.packageStore = packageStore;
        this.regularDictionaryStore = regularDictionaryStore;
    }

    @Override
    public List<String> readExpressions(String packageId, Integer packageVersion, String filter, Integer limit) {
        List<String> retExpressions = new LinkedList<String>();
        try {
            PackageConfiguration packageConfiguration = packageStore.read(packageId, packageVersion);
            List<IResourceStore.IResourceId> resourceIds = readDictionaryResourceIds(packageConfiguration);
            for (IResourceStore.IResourceId resourceId : resourceIds) {
                List<String> expressions = regularDictionaryStore.readExpressions(resourceId.getId(), resourceId.getVersion(), filter, "asc", 0, limit);
                CollectionUtilities.addAllWithoutDuplicates(retExpressions, expressions);
            }
            return retExpressions;
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (IResourceStore.ResourceStoreException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e);
        }
    }

    private List<IResourceStore.IResourceId> readDictionaryResourceIds(PackageConfiguration packageConfiguration) {
        List<IResourceStore.IResourceId> resourceIds = new LinkedList<IResourceStore.IResourceId>();

        for (PackageConfiguration.PackageExtension packageExtension : packageConfiguration.getPackageExtensions()) {
            if (!packageExtension.getType().toString().startsWith("core://io.sls.parser")) {
                continue;
            }

            Map<String, Object> extensionTypes = packageExtension.getExtensions();
            for (String extensionKey : extensionTypes.keySet()) {
                if (!extensionKey.equals("dictionaries")) {
                    continue;
                }

                List<Map<String, Object>> extensions = (List<Map<String, Object>>) extensionTypes.get(extensionKey);
                for (Map<String, Object> extension : extensions) {
                    if (!extension.containsKey("type") || !extension.get("type").toString().startsWith("core://io.sls.parser.dictionaries.regular")) {
                        continue;
                    }

                    Map<String, Object> config = (Map<String, Object>) extension.get("config");
                    String uri = (String) config.get("uri");
                    resourceIds.add(RestUtilities.extractResourceId(URI.create(uri)));
                }

            }
        }

        return resourceIds;
    }
}
