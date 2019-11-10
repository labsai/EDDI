package ai.labs.resources.impl.config.packages.rest;

import ai.labs.models.DocumentDescriptor;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.impl.resources.rest.RestVersionInfo;
import ai.labs.resources.rest.config.packages.IPackageStore;
import ai.labs.resources.rest.config.packages.IRestPackageStore;
import ai.labs.resources.rest.config.packages.model.PackageConfiguration;
import ai.labs.resources.rest.config.packages.model.PackageConfiguration.PackageExtension;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.rest.restinterfaces.factory.IRestInterfaceFactory;
import ai.labs.rest.restinterfaces.factory.RestInterfaceFactory;
import ai.labs.runtime.client.configuration.ResourceClientLibrary;
import ai.labs.runtime.service.ServiceException;
import ai.labs.schema.IJsonSchemaCreator;
import ai.labs.utilities.RestUtilities;
import ai.labs.utilities.URIUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static ai.labs.resources.impl.utilities.ResourceUtilities.createDocumentDescriptorForDuplicate;
import static ai.labs.resources.impl.utilities.ResourceUtilities.createMalFormattedResourceUriException;
import static ai.labs.resources.impl.utilities.ResourceUtilities.validateUri;
import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;

@Slf4j
public class RestPackageStore extends RestVersionInfo<PackageConfiguration> implements IRestPackageStore {
    private static final String KEY_CONFIG = "config";
    private static final String KEY_URI = "uri";
    private final IPackageStore packageStore;
    private final ResourceClientLibrary resourceClientLibrary;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private IRestPackageStore restPackageStore;

    @Inject
    public RestPackageStore(IPackageStore packageStore,
                            IRestInterfaceFactory restInterfaceFactory,
                            ResourceClientLibrary resourceClientLibrary,
                            IDocumentDescriptorStore documentDescriptorStore,
                            IJsonSchemaCreator jsonSchemaCreator) {
        super(resourceURI, packageStore, documentDescriptorStore);
        this.packageStore = packageStore;
        this.resourceClientLibrary = resourceClientLibrary;
        this.jsonSchemaCreator = jsonSchemaCreator;
        initRestClient(restInterfaceFactory);
    }

    private void initRestClient(IRestInterfaceFactory restInterfaceFactory) {
        try {
            restPackageStore = restInterfaceFactory.get(IRestPackageStore.class);
        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
            restPackageStore = null;
            log.error(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Response readJsonSchema() {
        return Response.ok(jsonSchemaCreator.generateSchema(PackageConfiguration.class)).build();
    }

    @Override
    public List<DocumentDescriptor> readPackageDescriptors(String filter, Integer index, Integer limit) {
        return readDescriptors("ai.labs.package", filter, index, limit);
    }

    @Override
    public List<DocumentDescriptor> readPackageDescriptors(String filter,
                                                           Integer index,
                                                           Integer limit,
                                                           String containingResourceUri,
                                                           Boolean includePreviousVersions) {

        if (validateUri(containingResourceUri) == null) {
            return createMalFormattedResourceUriException(containingResourceUri);
        }

        try {
            return packageStore.getPackageDescriptorsContainingResource(
                    containingResourceUri,
                    includePreviousVersions);
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
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
        for (PackageExtension packageExtension : packageConfiguration.getPackageExtensions()) {
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
            return Response.status(BAD_REQUEST).entity(uri).type(MediaType.TEXT_PLAIN).build();
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
    public Response duplicatePackage(String id, Integer version, Boolean deepCopy) {
        validateParameters(id, version);
        try {
            PackageConfiguration packageConfiguration = restPackageStore.readPackage(id, version);
            if (deepCopy) {
                for (var packageExtension : packageConfiguration.getPackageExtensions()) {
                    URI type = packageExtension.getType();
                    if ("ai.labs.parser".equals(type.getHost())) {
                        duplicateDictionaryInParser(packageExtension);
                    }

                    Map<String, Object> config = packageExtension.getConfig();
                    if (!isNullOrEmpty(config)) {
                        Object resourceUriObj = config.get(KEY_URI);
                        if (!isNullOrEmpty(resourceUriObj)) {
                            var newResourceLocation = duplicateResource(resourceUriObj);
                            config.put(KEY_URI, newResourceLocation);
                        }
                    }
                }
            }

            Response createPackageResponse = restPackageStore.createPackage(packageConfiguration);
            createDocumentDescriptorForDuplicate(documentDescriptorStore, id, version, createPackageResponse.getLocation());

            return createPackageResponse;
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    private void duplicateDictionaryInParser(PackageExtension packageExtension) throws ServiceException {
        URI type;
        var dictionaries = (List<Map<String, Object>>) packageExtension.getExtensions().get("dictionaries");
        if (!isNullOrEmpty(dictionaries)) {
            for (var dictionary : dictionaries) {
                type = URI.create(dictionary.get("type").toString());
                if ("ai.labs.parser.dictionaries.regular".equals(type.getHost())) {
                    var config = (Map<String, URI>) dictionary.get("config");
                    if (!isNullOrEmpty(config)) {
                        Object dictionaryUriObj = config.get(KEY_URI);
                        if (!isNullOrEmpty(dictionaryUriObj)) {
                            var newDictionaryLocation = duplicateResource(dictionaryUriObj);
                            config.put(KEY_URI, newDictionaryLocation);
                        }
                    }
                }
            }
        }
    }

    private URI duplicateResource(Object resourceUriObj) throws ServiceException {
        URI newResourceLocation = null;

        try {
            if (!isNullOrEmpty(resourceUriObj)) {
                URI oldResourceUri = URI.create(resourceUriObj.toString());

                Response duplicateResourceResponse = resourceClientLibrary.duplicateResource(oldResourceUri);

                newResourceLocation = duplicateResourceResponse.getLocation();

                var oldResourceId = URIUtilities.extractResourceId(oldResourceUri);
                createDocumentDescriptorForDuplicate(
                        documentDescriptorStore,
                        oldResourceId.getId(),
                        oldResourceId.getVersion(),
                        newResourceLocation);
            }
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }

        if (isNullOrEmpty(newResourceLocation)) {
            String errorMsg = String.format(
                    "New resource for %s could not be created. " +
                            "Mission Location Header.", resourceUriObj);
            throw new ServiceException(errorMsg);
        }

        return newResourceLocation;
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return packageStore.getCurrentResourceId(id);
    }
}
