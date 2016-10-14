package io.sls.core.runtime.client.configuration;

import io.sls.core.runtime.service.ServiceException;
import io.sls.core.service.restinterfaces.IRestInterfaceFactory;
import io.sls.core.utilities.URIUtilities;
import io.sls.resources.rest.behavior.IRestBehaviorStore;
import io.sls.resources.rest.output.IRestOutputStore;
import io.sls.resources.rest.regulardictionary.IRestRegularDictionaryStore;

import javax.inject.Inject;
import javax.inject.Named;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * User: jarisch
 * Date: 16.08.12
 * Time: 16:38
 */
public class ResourceClientLibrary implements IResourceClientLibrary {
    private final IRestInterfaceFactory restInterfaceFactory;
    private final String configurationServerURI;
    private Map<String, IResourceService> restInterfaces;
    private Map<URI, Object> resourceCache = new HashMap<>();

    @Inject
    public ResourceClientLibrary(IRestInterfaceFactory restInterfaceFactory,
                                 @Named("system.configurationServerURI") String configurationServerURI) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.configurationServerURI = configurationServerURI;
        init();
    }

    @Override
    public void init() throws ResourceClientLibraryException {
        this.restInterfaces = new HashMap<>();
        restInterfaces.put("io.sls.regulardictionary", (id, version) -> {
            try {
                IRestRegularDictionaryStore dictionaryStore = restInterfaceFactory.get(IRestRegularDictionaryStore.class, configurationServerURI);
                return dictionaryStore.readRegularDictionary(id, version, "", "", 0, 0);
            } catch (Exception e) {
                throw new ServiceException(e.getLocalizedMessage(), e);
            }
        });

        restInterfaces.put("io.sls.behavior", (id, version) -> {
            try {
                IRestBehaviorStore behaviorStore = restInterfaceFactory.get(IRestBehaviorStore.class, configurationServerURI);
                return behaviorStore.readBehaviorRuleSet(id, version);
            } catch (Exception e) {
                throw new ServiceException(e.getLocalizedMessage(), e);
            }
        });

        restInterfaces.put("io.sls.output", (id, version) -> {
            try {
                IRestOutputStore outputStore = restInterfaceFactory.get(IRestOutputStore.class, configurationServerURI);
                return outputStore.readOutputSet(id, version, "", "", 0, 0);
            } catch (Exception e) {
                throw new ServiceException(e.getLocalizedMessage(), e);
            }
        });
    }

    @Override
    public <T> T getResource(URI uri, Class<T> clazz) throws ServiceException {
        /*if(resourceCache.containsKey(uri))
        {
            return (T) resourceCache.get(uri);
        }*/

        String type = uri.getHost();
        IResourceService proxy = restInterfaces.get(type);

        if (proxy != null) {
            URIUtilities.ResourceId resourceId = URIUtilities.extractResourceId(uri);
            Object resource = proxy.read(resourceId.getId(), resourceId.getVersion());
//            resourceCache.put(uri, resource);
            return (T) resource;
        }

        return null;
    }

    private interface IResourceService {
        Object read(String id, Integer version) throws ServiceException;
    }

    public class ResourceClientLibraryException extends RuntimeException {
        public ResourceClientLibraryException(String message, Exception e) {
            super(message, e);
        }
    }
}
