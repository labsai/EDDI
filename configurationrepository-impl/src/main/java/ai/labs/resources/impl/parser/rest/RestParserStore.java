package ai.labs.resources.impl.parser.rest;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.impl.resources.rest.RestVersionInfo;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.parser.IParserStore;
import ai.labs.resources.rest.parser.IRestParserStore;
import ai.labs.resources.rest.parser.model.ParserConfiguration;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class RestParserStore extends RestVersionInfo<ParserConfiguration> implements IRestParserStore {
    private final IParserStore parserStore;

    @Inject
    public RestParserStore(IParserStore parserStore, IDocumentDescriptorStore documentDescriptorStore) {
        super(resourceURI, parserStore, documentDescriptorStore);
        this.parserStore = parserStore;
    }

    @Override
    public List<DocumentDescriptor> readParserDescriptors(String filter, Integer index, Integer limit) {
        return readDescriptors("ai.labs.parser", filter, index, limit);
    }

    @Override
    public Response readParser(String id, Integer version) {
        return read(id, version);
    }

    @Override
    public URI updateParser(String id, Integer version, ParserConfiguration parserConfiguration) {
        return update(id, version, parserConfiguration);
    }

    @Override
    public Response createParser(ParserConfiguration parserConfiguration) {
        return create(parserConfiguration);
    }

    @Override
    public void deleteParser(String id, Integer version) {
        delete(id, version);
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return parserStore.getCurrentResourceId(id);
    }
}
