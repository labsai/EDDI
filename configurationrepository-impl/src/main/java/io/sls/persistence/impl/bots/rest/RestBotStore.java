package io.sls.persistence.impl.bots.rest;

import io.sls.persistence.IResourceStore;
import io.sls.persistence.impl.resources.rest.RestVersionInfo;
import io.sls.resources.rest.IRestVersionInfo;
import io.sls.resources.rest.bots.IBotStore;
import io.sls.resources.rest.bots.IRestBotStore;
import io.sls.resources.rest.bots.model.BotConfiguration;
import io.sls.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class RestBotStore extends RestVersionInfo implements IRestBotStore {
    private final IBotStore botStore;
    private final IDocumentDescriptorStore documentDescriptorStore;

    @Inject
    public RestBotStore(IBotStore botStore,
                        IDocumentDescriptorStore documentDescriptorStore) {
        this.botStore = botStore;
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @Override
    public List<DocumentDescriptor> readBotDescriptors(String filter, Integer index, Integer limit) {
        try {
            return documentDescriptorStore.readDescriptors("io.sls.bot", filter, index, limit, false);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public BotConfiguration readBot(String id, Integer version) {
        return read(botStore, id, version);
    }

    @Override
    public URI updateBot(String id, Integer version, BotConfiguration botConfiguration) {
        return update(botStore, resourceURI, id, version, botConfiguration);
    }

    @Override
    public Response updateResourceInBot(String id, Integer version, URI resourceURI) {
        String resourceURIString = resourceURI.toString();
        String resourceURIWithoutVersion = resourceURIString.substring(0, resourceURIString.lastIndexOf("?"));

        boolean updated = false;
        BotConfiguration botConfiguration = readBot(id, version);
        List<URI> packages = botConfiguration.getPackages();
        for (int i = 0; i < packages.size(); i++) {
            URI packageURI = packages.get(i);
            if (packageURI.toString().startsWith(resourceURIWithoutVersion)) {
                packages.set(i, resourceURI);
                updated = true;
            }
        }

        if (updated) {
            return Response.ok(updateBot(id, version, botConfiguration)).build();
        } else {
            URI uri = RestUtilities.createURI(RestBotStore.resourceURI, id, IRestVersionInfo.versionQueryParam, version);
            return Response.status(Response.Status.BAD_REQUEST).entity(uri).build();
        }
    }

    @Override
    public Response createBot(BotConfiguration botConfiguration) {
        return create(botStore, resourceURI, botConfiguration);
    }

    @Override
    public void deleteBot(String id, Integer version) {
        delete(botStore, resourceURI, id, version);
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return botStore.getCurrentResourceId(id);
    }
}
