package ai.labs.resources.impl.bots.rest;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.impl.resources.rest.RestVersionInfo;
import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.bots.IBotStore;
import ai.labs.resources.rest.bots.IRestBotStore;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

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
    public List<DocumentDescriptor> readBotDescriptors(String filter, Integer index, Integer limit) {
        return readDescriptors("ai.labs.bot", filter, index, limit);
    }

    @Override
    public BotConfiguration readBot(String id, Integer version) {
        return read(id, version);
    }

    @Override
    public URI updateBot(String id, Integer version, BotConfiguration botConfiguration) {
        return update(id, version, botConfiguration);
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
        return create(botConfiguration);
    }

    @Override
    public void deleteBot(String id, Integer version) {
        delete(id, version);
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return botStore.getCurrentResourceId(id);
    }
}
