package ai.labs.eddi.configs.expression;

import ai.labs.eddi.configs.behavior.IBehaviorStore;
import ai.labs.eddi.configs.http.IHttpCallsStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.packages.IPackageStore;
import ai.labs.eddi.configs.packages.model.PackageConfiguration.PackageExtension;
import ai.labs.eddi.configs.regulardictionary.IRestAction;
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

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestAction implements IRestAction {
    private final IPackageStore packageStore;
    private final IBehaviorStore behaviorStore;
    private final IHttpCallsStore httpCallsStore;
    private final IOutputStore outputStore;

    private static final Logger log = Logger.getLogger(RestAction.class);

    @Inject
    public RestAction(IPackageStore packageStore,
                      IBehaviorStore behaviorStore,
                      IHttpCallsStore httpCallsStore,
                      IOutputStore outputStore) {
        this.packageStore = packageStore;
        this.behaviorStore = behaviorStore;
        this.httpCallsStore = httpCallsStore;
        this.outputStore = outputStore;
    }

    @Override
    public List<String> readActions(String packageId, Integer packageVersion, String filter, Integer limit) {
        List<String> retActions = new LinkedList<>();
        try {
            var packageConfiguration = packageStore.read(packageId, packageVersion);

            List<String> actions;
            for (var packageExtension : packageConfiguration.getPackageExtensions()) {
                var type = packageExtension.getType().toString();
                var resourceId = extractUriFromConfig(packageExtension);
                var id = resourceId.getId();
                var version = resourceId.getVersion();

                if (type.startsWith("eddi://ai.labs.behavior")) {
                    actions = behaviorStore.readActions(id, version, filter, limit);
                } else if (type.startsWith("eddi://ai.labs.httpcalls")) {
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
            throw new NotFoundException();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e);
        }
    }

    private static IResourceStore.IResourceId extractUriFromConfig(PackageExtension packageExtension) {
        var config = packageExtension.getConfig();
        var uri = URI.create(config.get("uri").toString());
        return RestUtilities.extractResourceId(uri);
    }
}
