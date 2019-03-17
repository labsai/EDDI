package ai.labs.runtime.client.configuration;

import ai.labs.resources.rest.behavior.IRestBehaviorStore;
import ai.labs.resources.rest.http.IRestHttpCallsStore;
import ai.labs.resources.rest.output.IRestOutputStore;
import ai.labs.resources.rest.parser.IRestParserStore;
import ai.labs.resources.rest.regulardictionary.IRestRegularDictionaryStore;
import ai.labs.rest.restinterfaces.IRestInterfaceFactory;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.URIUtilities;

import javax.inject.Inject;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */
public class ResourceClientLibrary implements IResourceClientLibrary {
    private final IRestInterfaceFactory restInterfaceFactory;
    private Map<String, IResourceService> restInterfaces;

    @Inject
    public ResourceClientLibrary(IRestInterfaceFactory restInterfaceFactory) {
        this.restInterfaceFactory = restInterfaceFactory;
        init();
    }

    @Override
    public void init() throws ResourceClientLibraryException {
        this.restInterfaces = new HashMap<>();
        restInterfaces.put("ai.labs.parser", (id, version) -> {
            try {
                IRestParserStore parserStore = restInterfaceFactory.get(IRestParserStore.class);
                return parserStore.readParser(id, version);
            } catch (Exception e) {
                throw new ServiceException(e.getLocalizedMessage(), e);
            }
        });

        restInterfaces.put("ai.labs.regulardictionary", (id, version) -> {
            try {
                IRestRegularDictionaryStore dictionaryStore = restInterfaceFactory.get(IRestRegularDictionaryStore.class);
                return dictionaryStore.readRegularDictionary(id, version, "", "", 0, 0);
            } catch (Exception e) {
                throw new ServiceException(e.getLocalizedMessage(), e);
            }
        });

        restInterfaces.put("ai.labs.behavior", (id, version) -> {
            try {
                IRestBehaviorStore behaviorStore = restInterfaceFactory.get(IRestBehaviorStore.class);
                return behaviorStore.readBehaviorRuleSet(id, version);
            } catch (Exception e) {
                throw new ServiceException(e.getLocalizedMessage(), e);
            }
        });

        restInterfaces.put("ai.labs.httpcalls", (id, version) -> {
            try {
                IRestHttpCallsStore httpCallsStore = restInterfaceFactory.get(IRestHttpCallsStore.class);
                return httpCallsStore.readHttpCalls(id, version);
            } catch (Exception e) {
                throw new ServiceException(e.getLocalizedMessage(), e);
            }
        });

        restInterfaces.put("ai.labs.output", (id, version) -> {
            try {
                IRestOutputStore outputStore = restInterfaceFactory.get(IRestOutputStore.class);
                return outputStore.readOutputSet(id, version, "", "", 0, 0);
            } catch (Exception e) {
                throw new ServiceException(e.getLocalizedMessage(), e);
            }
        });
    }

    @Override
    public <T> T getResource(URI uri, Class<T> clazz) throws ServiceException {
        String type = uri.getHost();
        IResourceService proxy = restInterfaces.get(type);

        if (proxy != null) {
            URIUtilities.ResourceId resourceId = URIUtilities.extractResourceId(uri);
            Object resource = proxy.read(resourceId.getId(), resourceId.getVersion());
            return (T) resource;
        }

        return null;
    }

    private interface IResourceService {
        Object read(String id, Integer version) throws ServiceException;
    }
}
