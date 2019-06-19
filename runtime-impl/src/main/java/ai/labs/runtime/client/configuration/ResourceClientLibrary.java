package ai.labs.runtime.client.configuration;

import ai.labs.persistence.model.ResourceId;
import ai.labs.resources.rest.config.behavior.IRestBehaviorStore;
import ai.labs.resources.rest.config.http.IRestHttpCallsStore;
import ai.labs.resources.rest.config.output.IRestOutputStore;
import ai.labs.resources.rest.config.parser.IRestParserStore;
import ai.labs.resources.rest.config.propertysetter.IRestPropertySetterStore;
import ai.labs.resources.rest.config.regulardictionary.IRestRegularDictionaryStore;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.RuntimeUtilities;
import ai.labs.utilities.URIUtilities;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */
public class ResourceClientLibrary implements IResourceClientLibrary {
    private final IRestParserStore restParserStore;
    private final IRestRegularDictionaryStore restRegularDictionaryStore;
    private final IRestBehaviorStore restBehaviorStore;
    private final IRestHttpCallsStore restHttpCallsStore;
    private final IRestOutputStore restOutputStore;
    private final IRestPropertySetterStore restPropertySetterStore;
    private Map<String, IResourceService> restInterfaces;

    @Inject
    public ResourceClientLibrary(IRestParserStore restParserStore,
                                 IRestRegularDictionaryStore restRegularDictionaryStore,
                                 IRestBehaviorStore restBehaviorStore,
                                 IRestHttpCallsStore restHttpCallsStore,
                                 IRestOutputStore restOutputStore,
                                 IRestPropertySetterStore restPropertySetterStore) {
        this.restParserStore = restParserStore;
        this.restRegularDictionaryStore = restRegularDictionaryStore;
        this.restBehaviorStore = restBehaviorStore;
        this.restHttpCallsStore = restHttpCallsStore;
        this.restOutputStore = restOutputStore;
        this.restPropertySetterStore = restPropertySetterStore;

        init();
    }

    @Override
    public void init() throws ResourceClientLibraryException {
        this.restInterfaces = new HashMap<>();
        restInterfaces.put("ai.labs.parser", new IResourceService() {
            @Override
            public Object read(String id, Integer version) {
                return restParserStore.readParser(id, version);
            }

            @Override
            public Response duplicate(String id, Integer version) {
                return restParserStore.duplicateParser(id, version);
            }
        });

        restInterfaces.put("ai.labs.regulardictionary", new IResourceService() {
            @Override
            public Object read(String id, Integer version) {
                return restRegularDictionaryStore.readRegularDictionary(id, version, "", "", 0, 0);
            }

            @Override
            public Response duplicate(String id, Integer version) {
                return restRegularDictionaryStore.duplicateRegularDictionary(id, version);
            }
        });

        restInterfaces.put("ai.labs.behavior", new IResourceService() {
            @Override
            public Object read(String id, Integer version) {
                return restBehaviorStore.readBehaviorRuleSet(id, version);
            }

            @Override
            public Response duplicate(String id, Integer version) {
                return restBehaviorStore.duplicateBehaviorRuleSet(id, version);
            }
        });

        restInterfaces.put("ai.labs.httpcalls", new IResourceService() {
            @Override
            public Object read(String id, Integer version) {
                return restHttpCallsStore.readHttpCalls(id, version);
            }

            @Override
            public Response duplicate(String id, Integer version) {
                return restHttpCallsStore.duplicateHttpCalls(id, version);
            }
        });

        restInterfaces.put("ai.labs.output", new IResourceService() {
            @Override
            public Object read(String id, Integer version) {
                return restOutputStore.readOutputSet(id, version, "", "", 0, 0);
            }

            @Override
            public Response duplicate(String id, Integer version) {
                return restOutputStore.duplicateOutputSet(id, version);
            }
        });

        restInterfaces.put("ai.labs.property", new IResourceService() {
            @Override
            public Object read(String id, Integer version) {
                return restPropertySetterStore.readPropertySetter(id, version);
            }

            @Override
            public Response duplicate(String id, Integer version) {
                return restPropertySetterStore.duplicatePropertySetter(id, version);
            }
        });
    }

    @Override
    public <T> T getResource(URI uri, Class<T> clazz) throws ServiceException {
        String type = uri.getHost();
        IResourceService proxy = restInterfaces.get(type);

        if (proxy != null) {
            ResourceId resourceId = URIUtilities.extractResourceId(uri);
            Object resource = proxy.read(resourceId.getId(), resourceId.getVersion());
            return (T) resource;
        }

        return null;
    }

    @Override
    public Response duplicateResource(URI uri) throws ServiceException {
        String type = uri.getHost();
        IResourceService proxy = restInterfaces.get(type);
        if (RuntimeUtilities.isNullOrEmpty(proxy)) {
            throw new ServiceException(String.format("Could not find proxy for type '%s' in uri '%s'", type, uri));
        }

        ResourceId resourceId = URIUtilities.extractResourceId(uri);
        return proxy.duplicate(resourceId.getId(), resourceId.getVersion());
    }

    private interface IResourceService {
        Object read(String id, Integer version) throws ServiceException;

        Response duplicate(String id, Integer version) throws ServiceException;
    }
}
