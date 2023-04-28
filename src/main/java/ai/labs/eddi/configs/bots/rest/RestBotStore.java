package ai.labs.eddi.configs.bots.rest;

import ai.labs.eddi.configs.bots.IBotStore;
import ai.labs.eddi.configs.bots.IRestBotStore;
import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.packages.IRestPackageStore;
import ai.labs.eddi.configs.packages.rest.RestPackageStore;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
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

import static ai.labs.eddi.configs.utilities.ResourceUtilities.*;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestBotStore implements IRestBotStore {
    private static final String PACKAGE_URI = IRestPackageStore.resourceURI;
    private final IBotStore botStore;
    private final IRestPackageStore restPackageStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<BotConfiguration> restVersionInfo;
    private final IDocumentDescriptorStore documentDescriptorStore;

    private static final Logger log = Logger.getLogger(RestBotStore.class);

    @Inject
    public RestBotStore(IBotStore botStore,
                        IRestPackageStore restPackageStore,
                        IDocumentDescriptorStore documentDescriptorStore,
                        IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, botStore, documentDescriptorStore);
        this.documentDescriptorStore = documentDescriptorStore;
        this.botStore = botStore;
        this.restPackageStore = restPackageStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(BotConfiguration.class)).build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public List<DocumentDescriptor> readBotDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.bot", filter, index, limit);
    }

    @Override
    public List<DocumentDescriptor> readBotDescriptors(String filter, Integer index, Integer limit,
                                                       String containingPackageUri, Boolean includePreviousVersions) {

        IResourceId validatedResourceId = validateUri(containingPackageUri);
        if (validatedResourceId == null || !containingPackageUri.startsWith(PACKAGE_URI)) {
            return createMalFormattedResourceUriException(containingPackageUri);
        }

        try {
            return botStore.getBotDescriptorsContainingPackage(
                    validatedResourceId.getId(), validatedResourceId.getVersion(), includePreviousVersions);
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public BotConfiguration readBot(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateBot(String id, Integer version, BotConfiguration botConfiguration) {
        return restVersionInfo.update(id, version, botConfiguration);
    }

    @Override
    public Response updateResourceInBot(String id, Integer version, URI resourceURI) {
        String resourceURIString = resourceURI.toString();
        String resourceURIWithoutVersion = resourceURIString.substring(0, resourceURIString.lastIndexOf("?"));

        boolean updated = false;
        BotConfiguration botConfiguration = readBot(id, version);
        List<URI> packages = botConfiguration.getPackages();
        for (int index = 0; index < packages.size(); index++) {
            URI packageURI = packages.get(index);
            if (packageURI.toString().startsWith(resourceURIWithoutVersion)) {
                packages.set(index, resourceURI);
                updated = true;
            }
        }

        if (updated) {
            return updateBot(id, version, botConfiguration);
        } else {
            URI uri = RestUtilities.createURI(RestPackageStore.resourceURI, id, versionQueryParam, version);
            return Response.status(BAD_REQUEST).entity(uri).type(MediaType.TEXT_PLAIN).build();
        }
    }

    @Override
    public Response createBot(BotConfiguration botConfiguration) {
        return restVersionInfo.create(botConfiguration);
    }

    @Override
    public Response duplicateBot(String id, Integer version, Boolean deepCopy) {
        restVersionInfo.validateParameters(id, version);
        try {
            BotConfiguration botConfiguration = botStore.read(id, version);
            if (deepCopy) {
                List<URI> packages = botConfiguration.getPackages();
                for (int i = 0; i < packages.size(); i++) {
                    URI packageUri = packages.get(i);
                    IResourceId resourceId = RestUtilities.extractResourceId(packageUri);
                    Response duplicateResourceResponse = restPackageStore.
                            duplicatePackage(resourceId.getId(), resourceId.getVersion(), true);
                    URI newResourceLocation = duplicateResourceResponse.getLocation();
                    packages.set(i, newResourceLocation);
                }
            }

            Response createBotResponse = restVersionInfo.create(botConfiguration);
            createDocumentDescriptorForDuplicate(documentDescriptorStore, id, version, createBotResponse.getLocation());

            return createBotResponse;
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public Response deleteBot(String id, Integer version) {
        return restVersionInfo.delete(id, version);
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return botStore.getCurrentResourceId(id);
    }
}
