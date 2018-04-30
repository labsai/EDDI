package ai.labs.resources.impl.bots.rest;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.impl.packages.rest.RestPackageStore;
import ai.labs.resources.impl.resources.rest.RestVersionInfo;
import ai.labs.resources.rest.bots.IBotStore;
import ai.labs.resources.rest.bots.IRestBotStore;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;

/**
 * @author ginccc
 */
@Slf4j
public class RestBotStore extends RestVersionInfo<BotConfiguration> implements IRestBotStore {
    private final IBotStore botStore;

    @Inject
    public RestBotStore(IBotStore botStore,
                        IDocumentDescriptorStore documentDescriptorStore) {
        super(resourceURI, botStore, documentDescriptorStore);
        this.botStore = botStore;
    }

    @Override
    public List<DocumentDescriptor> readBotDescriptors(String filter, Integer index, Integer limit,
                                                       String packageId, Integer packageVersion) {
        if (packageId != null && packageVersion != null) {
            try {
                return botStore.getBotDescriptorsContainingPackage(packageId, packageVersion);
            } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
                log.error(e.getLocalizedMessage(), e);
                throw new InternalServerErrorException();
            }
        } else {
            if (packageId != null) {
                return createBadRequestException("packageVersion");
            }

            if (packageVersion != null) {
                return createBadRequestException("packageId");
            }

            return readDescriptors("ai.labs.bot", filter, index, limit);
        }
    }

    private List<DocumentDescriptor> createBadRequestException(String paramName) {
        String message = String.format("query param '%s' is missing", paramName);
        throw new BadRequestException(Response.status(BAD_REQUEST).entity(message).type(MediaType.TEXT_PLAIN).build());
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
    public Response deleteBot(String id, Integer version) {
        return delete(id, version);
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return botStore.getCurrentResourceId(id);
    }
}
