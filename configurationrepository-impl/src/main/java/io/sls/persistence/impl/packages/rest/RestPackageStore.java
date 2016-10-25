package io.sls.persistence.impl.packages.rest;

import io.sls.persistence.IResourceStore;
import io.sls.persistence.impl.resources.rest.RestVersionInfo;
import io.sls.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.resources.rest.packages.IPackageStore;
import io.sls.resources.rest.packages.IRestPackageStore;
import io.sls.resources.rest.packages.model.PackageConfiguration;
import io.sls.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Map;

@Slf4j
public class RestPackageStore extends RestVersionInfo implements IRestPackageStore {
    public static final String KEY_URI = "uri";
    private static final String KEY_CONFIG = "config";
    public static final String KEY_EXTENSIONS = "extensions";
    private final IPackageStore packageStore;
    private final IDocumentDescriptorStore documentDescriptorStore;


    @Inject
    public RestPackageStore(IPackageStore packageStore,
                            IDocumentDescriptorStore documentDescriptorStore) {
        this.packageStore = packageStore;
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @Override
    public List<DocumentDescriptor> readPackageDescriptors(String filter, Integer index, Integer limit) {
        try {
            return documentDescriptorStore.readDescriptors("io.sls.package", filter, index, limit, false);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public PackageConfiguration readPackage(String id, Integer version) {
        try {
            return packageStore.read(id, version);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public URI updatePackage(String id, Integer version, PackageConfiguration packageConfiguration) {
        try {
            Integer newVersion = packageStore.update(id, version, packageConfiguration);
            return RestUtilities.createURI(resourceURI, id, versionQueryParam, newVersion);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceModifiedException e) {
            try {
                IResourceStore.IResourceId currentId = packageStore.getCurrentResourceId(id);
                throw RestUtilities.createConflictException(resourceURI, currentId);
            } catch (IResourceStore.ResourceNotFoundException e1) {
                throw new NotFoundException(e.getLocalizedMessage(), e);
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
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
            return Response.ok(updatePackage(id, version, packageConfiguration)).build();
        } else {
            URI uri = RestUtilities.createURI(RestPackageStore.resourceURI, id, versionQueryParam, version);
            return Response.status(Response.Status.BAD_REQUEST).entity(uri).build();
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
        try {
            IResourceStore.IResourceId resourceId = packageStore.create(packageConfiguration);
            URI createdUri = RestUtilities.createURI(resourceURI, resourceId.getId(), versionQueryParam, resourceId.getVersion());
            return Response.created(createdUri).entity(createdUri).build();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void deletePackage(String id, Integer version) {
        try {
            packageStore.delete(id, version);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceModifiedException e) {
            try {
                IResourceStore.IResourceId currentId = packageStore.getCurrentResourceId(id);
                throw RestUtilities.createConflictException(resourceURI, currentId);
            } catch (IResourceStore.ResourceNotFoundException e1) {
                throw new NotFoundException(e.getLocalizedMessage(), e);
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return packageStore.getCurrentResourceId(id);
    }
}
