package ai.labs.resources.impl.packages.rest;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.impl.resources.rest.RestVersionInfo;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.packages.IPackageStore;
import ai.labs.resources.rest.packages.IRestPackageStore;
import ai.labs.resources.rest.packages.model.PackageConfiguration;
import ai.labs.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Map;

@Slf4j
public class RestPackageStore extends RestVersionInfo<PackageConfiguration> implements IRestPackageStore {
    private static final String KEY_URI = "uri";
    private static final String KEY_CONFIG = "config";
    private final IPackageStore packageStore;

    @Inject
    public RestPackageStore(IPackageStore packageStore,
                            IDocumentDescriptorStore documentDescriptorStore) {
        super(resourceURI, packageStore, documentDescriptorStore);
        this.packageStore = packageStore;
    }

    @Override
    public List<DocumentDescriptor> readPackageDescriptors(String filter, Integer index, Integer limit) {
        return readDescriptors("ai.labs.package", filter, index, limit);
    }

    @Override
    public PackageConfiguration readPackage(String id, Integer version) {
        return read(id, version);
    }

    @Override
    public Response updatePackage(String id, Integer version, PackageConfiguration packageConfiguration) {
        return update(id, version, packageConfiguration);
    }

    @Override
    public Response updateResourceInPackage(String id, Integer version, URI resourceURI) {
        String resourceURIString = resourceURI.toString();
        String resourceURIWithoutVersion = resourceURIString.substring(0, resourceURIString.lastIndexOf("?"));

        boolean updated = false;
        PackageConfiguration packageConfiguration = readPackage(id, version);
        for (PackageConfiguration.PackageExtension packageExtension : packageConfiguration.getPackageExtensions()) {
            Map<String, Object> packageConfig = packageExtension.getConfig();
            if (updateResourceURI(resourceURI, resourceURIWithoutVersion, packageConfig)) {
                updated = true;
            }

            Map<String, Object> extensions = packageExtension.getExtensions();
            for (String extensionKey : extensions.keySet()) {
                List<Map<String, Object>> extensionElements = (List<Map<String, Object>>) extensions.get(extensionKey);
                for (Map<String, Object> extensionElement : extensionElements) {
                    if (extensionElement.containsKey(KEY_CONFIG)) {
                        Map<String, Object> config = (Map<String, Object>) extensionElement.get(KEY_CONFIG);
                        if (updateResourceURI(resourceURI, resourceURIWithoutVersion, config)) {
                            updated = true;
                        }
                    }
                }
            }
        }

        if (updated) {
            return updatePackage(id, version, packageConfiguration);
        } else {
            URI uri = RestUtilities.createURI(RestPackageStore.resourceURI, id, versionQueryParam, version);
            return Response.status(Response.Status.BAD_REQUEST).entity(uri).type(MediaType.TEXT_PLAIN).build();
        }
    }

    private boolean updateResourceURI(URI resourceURI, String resourceURIWithoutVersion, Map<String, Object> config) {
        if (config.containsKey(KEY_URI)) {
            Object uri = config.get(KEY_URI);
            if (uri.toString().startsWith(resourceURIWithoutVersion)) {
                // found resource URI to update
                config.put(KEY_URI, resourceURI);
                return true;
            }
        }

        return false;
    }

    @Override
    public Response createPackage(PackageConfiguration packageConfiguration) {
        return create(packageConfiguration);
    }

    @Override
    public Response deletePackage(String id, Integer version) {
        return delete(id, version);
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return packageStore.getCurrentResourceId(id);
    }
}
