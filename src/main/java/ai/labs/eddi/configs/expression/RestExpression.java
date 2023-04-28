package ai.labs.eddi.configs.expression;

import ai.labs.eddi.configs.packages.IPackageStore;
import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.configs.regulardictionary.IRegularDictionaryStore;
import ai.labs.eddi.configs.regulardictionary.IRestExpression;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.utils.CollectionUtilities;
import ai.labs.eddi.utils.RestUtilities;
import org.jboss.logging.Logger;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestExpression implements IRestExpression {
    private final IPackageStore packageStore;
    private final IRegularDictionaryStore regularDictionaryStore;

    private static final Logger log = Logger.getLogger(RestExpression.class);

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
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND.getStatusCode());
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
