package ai.labs.resources.impl.expression;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.config.packages.IPackageStore;
import ai.labs.resources.rest.config.packages.model.PackageConfiguration;
import ai.labs.resources.rest.config.regulardictionary.IRegularDictionaryStore;
import ai.labs.resources.rest.config.regulardictionary.IRestExpression;
import ai.labs.utilities.CollectionUtilities;
import ai.labs.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@Slf4j
public class RestExpression implements IRestExpression {
    private final IPackageStore packageStore;
    private final IRegularDictionaryStore regularDictionaryStore;

    @Inject
    public RestExpression(IPackageStore packageStore,
                          IRegularDictionaryStore regularDictionaryStore) {
        this.packageStore = packageStore;
        this.regularDictionaryStore = regularDictionaryStore;
    }

    @Override
    public List<String> readExpressions(String packageId, Integer packageVersion, String filter, Integer limit) {
        List<String> retExpressions = new LinkedList<>();
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
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e);
        }
    }

    private List<IResourceStore.IResourceId> readDictionaryResourceIds(PackageConfiguration packageConfiguration) {
        List<IResourceStore.IResourceId> resourceIds = new LinkedList<>();

        for (PackageConfiguration.PackageExtension packageExtension : packageConfiguration.getPackageExtensions()) {
            if (!packageExtension.getType().toString().startsWith("eddi://ai.labs.parser")) {
                continue;
            }

            Map<String, Object> extensionTypes = packageExtension.getExtensions();
            for (String extensionKey : extensionTypes.keySet()) {
                if (!extensionKey.equals("dictionaries")) {
                    continue;
                }

                List<Map<String, Object>> extensions = (List<Map<String, Object>>) extensionTypes.get(extensionKey);
                for (Map<String, Object> extension : extensions) {
                    if (!extension.containsKey("type") || !extension.get("type").toString().startsWith("eddi://ai.labs.parser.dictionaries.regular")) {
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
