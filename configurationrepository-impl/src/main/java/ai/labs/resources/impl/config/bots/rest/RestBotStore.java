package ai.labs.resources.impl.config.bots.rest;

import ai.labs.models.DocumentDescriptor;
import ai.labs.persistence.IResourceStore;
import ai.labs.persistence.model.ResourceId;
import ai.labs.resources.impl.config.packages.rest.RestPackageStore;
import ai.labs.resources.impl.resources.rest.RestVersionInfo;
import ai.labs.resources.rest.config.bots.IBotStore;
import ai.labs.resources.rest.config.bots.IRestBotStore;
import ai.labs.resources.rest.config.bots.model.BotConfiguration;
import ai.labs.resources.rest.config.packages.IRestPackageStore;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.rest.restinterfaces.factory.IRestInterfaceFactory;
import ai.labs.rest.restinterfaces.factory.RestInterfaceFactory;
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

import static ai.labs.resources.impl.utilities.ResourceUtilities.createDocumentDescriptorForDuplicate;
import static ai.labs.resources.impl.utilities.ResourceUtilities.createMalFormattedResourceUriException;
import static ai.labs.resources.impl.utilities.ResourceUtilities.validateUri;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;

/**
 * @author ginccc
 */
@Slf4j
public class RestBotStore extends RestVersionInfo<BotConfiguration> implements IRestBotStore {
    private static final String PACKAGE_URI = IRestPackageStore.resourceURI;
    private final IBotStore botStore;
    private final IRestPackageStore restPackageStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private IRestBotStore restBotStore;

    @Inject
    public RestBotStore(IBotStore botStore,
                        IRestPackageStore restPackageStore,
                        IRestInterfaceFactory restInterfaceFactory,
                        IDocumentDescriptorStore documentDescriptorStore,
                        IJsonSchemaCreator jsonSchemaCreator) {
        super(resourceURI, botStore, documentDescriptorStore);
        this.botStore = botStore;
        this.restPackageStore = restPackageStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
        initRestClient(restInterfaceFactory);
    }

    private void initRestClient(IRestInterfaceFactory restInterfaceFactory) {
        try {
            restBotStore = restInterfaceFactory.get(IRestBotStore.class);
        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
            restBotStore = null;
            log.error(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Response readJsonSchema() {
        return Response.ok(jsonSchemaCreator.generateSchema(BotConfiguration.class)).build();
    }

    @Override
    public List<DocumentDescriptor> readBotDescriptors(String filter, Integer index, Integer limit) {
        return readDescriptors("ai.labs.bot", filter, index, limit);
    }

    @Override
    public List<DocumentDescriptor> readBotDescriptors(String filter, Integer index, Integer limit, String containingPackageUri, Boolean includePreviousVersions) {
        IResourceStore.IResourceId validatedResourceId = validateUri(containingPackageUri);
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
        return read(id, version);
    }

    @Override
    public Response updateBot(String id, Integer version, BotConfiguration botConfiguration) {
        return update(id, version, botConfiguration);
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
        return create(botConfiguration);
    }

    @Override
    public Response duplicateBot(String id, Integer version, Boolean deepCopy) {
        validateParameters(id, version);
        BotConfiguration botConfiguration = restBotStore.readBot(id, version);
        if (deepCopy) {
            List<URI> packages = botConfiguration.getPackages();
            for (int i = 0; i < packages.size(); i++) {
                URI packageUri = packages.get(i);
                ResourceId resourceId = URIUtilities.extractResourceId(packageUri);
                Response duplicateResourceResponse = restPackageStore.
                        duplicatePackage(resourceId.getId(), resourceId.getVersion(), true);
                URI newResourceLocation = duplicateResourceResponse.getLocation();
                packages.set(i, newResourceLocation);
            }
        }

        try {
            Response createBotResponse = restBotStore.createBot(botConfiguration);
            createDocumentDescriptorForDuplicate(documentDescriptorStore, id, version, createBotResponse.getLocation());

            return createBotResponse;
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public Response deleteBot(String id, Integer version) {
        return delete(id, version);
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return botStore.getCurrentResourceId(id);
    }
}
