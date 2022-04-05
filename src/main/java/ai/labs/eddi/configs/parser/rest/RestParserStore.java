package ai.labs.eddi.configs.parser.rest;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.parser.IParserStore;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.IRestInterfaceFactory;
import ai.labs.eddi.engine.RestInterfaceFactory;
import ai.labs.eddi.models.DocumentDescriptor;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestParserStore implements IRestParserStore {
    private final IParserStore parserStore;
    private final RestVersionInfo<ParserConfiguration> restVersionInfo;
    private IRestParserStore restParserStore;

    @Inject
    Logger log;

    @Inject
    public RestParserStore(IParserStore parserStore,
                           IRestInterfaceFactory restInterfaceFactory,
                           IDocumentDescriptorStore documentDescriptorStore) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, parserStore, documentDescriptorStore);
        this.parserStore = parserStore;
        initRestClient(restInterfaceFactory);
    }

    private void initRestClient(IRestInterfaceFactory restInterfaceFactory) {
        try {
            restParserStore = restInterfaceFactory.get(IRestParserStore.class);
        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
            restParserStore = null;
            log.error(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public List<DocumentDescriptor> readParserDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.parser", filter, index, limit);
    }

    @Override
    public ParserConfiguration readParser(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateParser(String id, Integer version, ParserConfiguration parserConfiguration) {
        return restVersionInfo.update(id, version, parserConfiguration);
    }

    @Override
    public Response createParser(ParserConfiguration parserConfiguration) {
        return restVersionInfo.create(parserConfiguration);
    }

    @Override
    public Response deleteParser(String id, Integer version) {
        return restVersionInfo.delete(id, version);
    }

    @Override
    public Response duplicateParser(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        ParserConfiguration parserConfiguration = restParserStore.readParser(id, version);
        return restParserStore.createParser(parserConfiguration);
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return parserStore.getCurrentResourceId(id);
    }
}
