package ai.labs.resources.impl.config.parser.rest;

import ai.labs.models.DocumentDescriptor;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.impl.resources.rest.RestVersionInfo;
import ai.labs.resources.rest.config.parser.IParserStore;
import ai.labs.resources.rest.config.parser.IRestParserStore;
import ai.labs.resources.rest.config.parser.model.ParserConfiguration;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.rest.restinterfaces.factory.IRestInterfaceFactory;
import ai.labs.rest.restinterfaces.factory.RestInterfaceFactory;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class RestParserStore extends RestVersionInfo<ParserConfiguration> implements IRestParserStore {
    private final IParserStore parserStore;
    private IRestParserStore restParserStore;

    @Inject
    public RestParserStore(IParserStore parserStore,
                           IRestInterfaceFactory restInterfaceFactory,
                           IDocumentDescriptorStore documentDescriptorStore) {
        super(resourceURI, parserStore, documentDescriptorStore);
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
        return readDescriptors("ai.labs.parser", filter, index, limit);
    }

    @Override
    public ParserConfiguration readParser(String id, Integer version) {
        return read(id, version);
    }

    @Override
    public Response updateParser(String id, Integer version, ParserConfiguration parserConfiguration) {
        return update(id, version, parserConfiguration);
    }

    @Override
    public Response createParser(ParserConfiguration parserConfiguration) {
        return create(parserConfiguration);
    }

    @Override
    public Response deleteParser(String id, Integer version) {
        return delete(id, version);
    }

    @Override
    public Response duplicateParser(String id, Integer version) {
        validateParameters(id, version);
        ParserConfiguration parserConfiguration = restParserStore.readParser(id, version);
        return restParserStore.createParser(parserConfiguration);
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return parserStore.getCurrentResourceId(id);
    }
}
