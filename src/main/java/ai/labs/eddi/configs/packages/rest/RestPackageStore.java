package ai.labs.eddi.configs.packages.rest;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.output.rest.RestOutputStore;
import ai.labs.eddi.configs.packages.IPackageStore;
import ai.labs.eddi.configs.packages.IRestPackageStore;
import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.configs.packages.model.PackageConfiguration.PackageExtension;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.ResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.models.DocumentDescriptor;
import ai.labs.eddi.utils.RestUtilities;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.configs.utilities.ResourceUtilities.*;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;

@ApplicationScoped
public class RestPackageStore implements IRestPackageStore {
    private static final String KEY_CONFIG = "config";
    private static final String KEY_URI = "uri";
    private final IPackageStore packageStore;
    private final ResourceClientLibrary resourceClientLibrary;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<PackageConfiguration> restVersionInfo;
    private final IDocumentDescriptorStore documentDescriptorStore;

    private static final Logger log = Logger.getLogger(RestOutputStore.class);

    @Inject
    public RestPackageStore(IPackageStore packageStore,
                            ResourceClientLibrary resourceClientLibrary,
                            IDocumentDescriptorStore documentDescriptorStore,
                            IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, packageStore, documentDescriptorStore);
        this.documentDescriptorStore = documentDescriptorStore;
        this.packageStore = packageStore;
        this.resourceClientLibrary = resourceClientLibrary;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(PackageConfiguration.class)).build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public List<DocumentDescriptor> readPackageDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.package", filter, index, limit);
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
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updatePackage(String id, Integer version, PackageConfiguration packageConfiguration) {
        return restVersionInfo.update(id, version, packageConfiguration);
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
        return restVersionInfo.create(packageConfiguration);
    }

    @Override
    public Response deletePackage(String id, Integer version) {
        return restVersionInfo.delete(id, version);
    }

    @Override
    public Response duplicatePackage(String id, Integer version, Boolean deepCopy) {
        restVersionInfo.validateParameters(id, version);
        try {
            PackageConfiguration packageConfiguration = packageStore.read(id, version);
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

            Response createPackageResponse = restVersionInfo.create(packageConfiguration);
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

                var oldResourceId = RestUtilities.extractResourceId(oldResourceUri);
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
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return packageStore.getCurrentResourceId(id);
    }
}
