package ai.labs.eddi.engine.runtime.client.configuration;

import ai.labs.eddi.configs.rules.IRestBehaviorStore;
import ai.labs.eddi.configs.apicalls.IRestHttpCallsStore;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.dictionary.IRestRegularDictionaryStore;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.utils.RestUtilities;
import ai.labs.eddi.utils.RuntimeUtilities;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static ai.labs.eddi.datastore.IResourceStore.IResourceId;

/**
 * @author ginccc
 */
@ApplicationScoped
public class ResourceClientLibrary implements IResourceClientLibrary {
    private final IRestParserStore restParserStore;
    private final IRestRegularDictionaryStore restRegularDictionaryStore;
    private final IRestBehaviorStore restBehaviorStore;
    private final IRestHttpCallsStore restHttpCallsStore;
    private final IRestLlmStore restLlmStore;
    private final IRestOutputStore restOutputStore;
    private final IRestPropertySetterStore restPropertySetterStore;
    private Map<String, IResourceService> restInterfaces;

    private static final Logger log = Logger.getLogger(ResourceClientLibrary.class);

    @Inject
    public ResourceClientLibrary(IRestParserStore restParserStore,
            IRestRegularDictionaryStore restRegularDictionaryStore,
            IRestBehaviorStore restBehaviorStore,
            IRestHttpCallsStore restHttpCallsStore,
            IRestLlmStore restLlmStore,
            IRestOutputStore restOutputStore,
            IRestPropertySetterStore restPropertySetterStore) {
        this.restParserStore = restParserStore;
        this.restRegularDictionaryStore = restRegularDictionaryStore;
        this.restBehaviorStore = restBehaviorStore;
        this.restHttpCallsStore = restHttpCallsStore;
        this.restLlmStore = restLlmStore;
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

            @Override
            public Response delete(String id, Integer version, boolean permanent) {
                return restParserStore.deleteParser(id, version, permanent);
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

            @Override
            public Response delete(String id, Integer version, boolean permanent) {
                return restRegularDictionaryStore.deleteRegularDictionary(id, version, permanent);
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

            @Override
            public Response delete(String id, Integer version, boolean permanent) {
                return restBehaviorStore.deleteBehaviorRuleSet(id, version, permanent);
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

            @Override
            public Response delete(String id, Integer version, boolean permanent) {
                return restHttpCallsStore.deleteHttpCalls(id, version, permanent);
            }
        });

        restInterfaces.put("ai.labs.llm", new IResourceService() {
            @Override
            public Object read(String id, Integer version) {
                return restLlmStore.readLlm(id, version);
            }

            @Override
            public Response duplicate(String id, Integer version) {
                return restLlmStore.duplicateLlm(id, version);
            }

            @Override
            public Response delete(String id, Integer version, boolean permanent) {
                return restLlmStore.deleteLlm(id, version, permanent);
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

            @Override
            public Response delete(String id, Integer version, boolean permanent) {
                return restOutputStore.deleteOutputSet(id, version, permanent);
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

            @Override
            public Response delete(String id, Integer version, boolean permanent) {
                return restPropertySetterStore.deletePropertySetter(id, version, permanent);
            }
        });
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getResource(URI uri, Class<T> clazz) throws ServiceException {
        String type = uri.getHost();
        IResourceService proxy = restInterfaces.get(type);

        if (proxy != null) {
            IResourceId resourceId = RestUtilities.extractResourceId(uri);
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

        IResourceId resourceId = RestUtilities.extractResourceId(uri);
        return proxy.duplicate(resourceId.getId(), resourceId.getVersion());
    }

    @Override
    public Response deleteResource(URI uri, boolean permanent) throws ServiceException {
        String type = uri.getHost();
        IResourceService proxy = restInterfaces.get(type);
        if (RuntimeUtilities.isNullOrEmpty(proxy)) {
            log.warnf("Could not find proxy for type '%s' in uri '%s' — skipping delete", type, uri);
            return Response.ok().build();
        }

        IResourceId resourceId = RestUtilities.extractResourceId(uri);
        return proxy.delete(resourceId.getId(), resourceId.getVersion(), permanent);
    }

    private interface IResourceService {
        Object read(String id, Integer version) throws ServiceException;

        Response duplicate(String id, Integer version) throws ServiceException;

        Response delete(String id, Integer version, boolean permanent) throws ServiceException;
    }
}
