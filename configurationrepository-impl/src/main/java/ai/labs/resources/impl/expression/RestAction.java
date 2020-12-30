package ai.labs.resources.impl.expression;

import ai.labs.persistence.IResourceStore;
import ai.labs.persistence.IResourceStore.IResourceId;
import ai.labs.resources.rest.config.behavior.IBehaviorStore;
import ai.labs.resources.rest.config.http.IHttpCallsStore;
import ai.labs.resources.rest.config.output.IOutputStore;
import ai.labs.resources.rest.config.packages.IPackageStore;
import ai.labs.resources.rest.config.packages.model.PackageConfiguration.PackageExtension;
import ai.labs.resources.rest.config.regulardictionary.IRestAction;
import ai.labs.utilities.CollectionUtilities;
import ai.labs.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class RestAction implements IRestAction {
    private final IPackageStore packageStore;
    private final IBehaviorStore behaviorStore;
    private final IHttpCallsStore httpCallsStore;
    private final IOutputStore outputStore;

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
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e);
        }
    }

    private static IResourceId extractUriFromConfig(PackageExtension packageExtension) {
        var config = packageExtension.getConfig();
        var uri = URI.create(config.get("uri").toString());
        return RestUtilities.extractResourceId(uri);
    }
}
