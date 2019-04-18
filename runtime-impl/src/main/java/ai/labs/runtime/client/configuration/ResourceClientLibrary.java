package ai.labs.runtime.client.configuration;

import ai.labs.resources.rest.behavior.IRestBehaviorStore;
import ai.labs.resources.rest.http.IRestHttpCallsStore;
import ai.labs.resources.rest.output.IRestOutputStore;
import ai.labs.resources.rest.parser.IRestParserStore;
import ai.labs.resources.rest.regulardictionary.IRestRegularDictionaryStore;
import ai.labs.rest.restinterfaces.IRestInterfaceFactory;
import ai.labs.rest.restinterfaces.RestInterfaceFactory;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.RuntimeUtilities;
import ai.labs.utilities.URIUtilities;
import ai.labs.utilities.URIUtilities.ResourceId;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
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
        restInterfaces.put("ai.labs.parser", new IResourceService() {
            @Override
            public Object read(String id, Integer version) throws ServiceException {
                {
                    try {
                        return restInterfaceFactory.get(IRestParserStore.class).readParser(id, version);
                    } catch (Exception e) {
                        throw new ServiceException(e.getLocalizedMessage(), e);
                    }
                }
            }

            @Override
            public Response duplicate(String id, Integer version) throws ServiceException {
                try {
                    return restInterfaceFactory.get(IRestParserStore.class).duplicateParser(id, version);
                } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
                    throw new ServiceException(e.getLocalizedMessage(), e);
                }
            }
        });

        restInterfaces.put("ai.labs.regulardictionary", new IResourceService() {
            @Override
            public Object read(String id, Integer version) throws ServiceException {
                {
                    try {
                        IRestRegularDictionaryStore dictionaryStore = restInterfaceFactory.get(IRestRegularDictionaryStore.class);
                        return dictionaryStore.readRegularDictionary(id, version, "", "", 0, 0);
                    } catch (Exception e) {
                        throw new ServiceException(e.getLocalizedMessage(), e);
                    }
                }
            }

            @Override
            public Response duplicate(String id, Integer version) throws ServiceException {
                try {
                    return restInterfaceFactory.get(IRestRegularDictionaryStore.class).duplicateRegularDictionary(id, version);
                } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
                    throw new ServiceException(e.getLocalizedMessage(), e);
                }
            }
        });

        restInterfaces.put("ai.labs.behavior", new IResourceService() {
            @Override
            public Object read(String id, Integer version) throws ServiceException {
                {
                    try {
                        IRestBehaviorStore behaviorStore = restInterfaceFactory.get(IRestBehaviorStore.class);
                        return behaviorStore.readBehaviorRuleSet(id, version);
                    } catch (Exception e) {
                        throw new ServiceException(e.getLocalizedMessage(), e);
                    }
                }
            }

            @Override
            public Response duplicate(String id, Integer version) throws ServiceException {
                try {
                    return restInterfaceFactory.get(IRestBehaviorStore.class).duplicateBehaviorRuleSet(id, version);
                } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
                    throw new ServiceException(e.getLocalizedMessage(), e);
                }
            }
        });

        restInterfaces.put("ai.labs.httpcalls", new IResourceService() {
            @Override
            public Object read(String id, Integer version) throws ServiceException {
                {
                    try {
                        IRestHttpCallsStore httpCallsStore = restInterfaceFactory.get(IRestHttpCallsStore.class);
                        return httpCallsStore.readHttpCalls(id, version);
                    } catch (Exception e) {
                        throw new ServiceException(e.getLocalizedMessage(), e);
                    }
                }
            }

            @Override
            public Response duplicate(String id, Integer version) throws ServiceException {
                try {
                    return restInterfaceFactory.get(IRestHttpCallsStore.class).duplicateHttpCalls(id, version);
                } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
                    throw new ServiceException(e.getLocalizedMessage(), e);
                }
            }
        });

        restInterfaces.put("ai.labs.output", new IResourceService() {
            @Override
            public Object read(String id, Integer version) throws ServiceException {
                {
                    try {
                        IRestOutputStore outputStore = restInterfaceFactory.get(IRestOutputStore.class);
                        return outputStore.readOutputSet(id, version, "", "", 0, 0);
                    } catch (Exception e) {
                        throw new ServiceException(e.getLocalizedMessage(), e);
                    }
                }
            }

            @Override
            public Response duplicate(String id, Integer version) throws ServiceException {
                try {
                    return restInterfaceFactory.get(IRestOutputStore.class).duplicateOutputSet(id, version);
                } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
                    throw new ServiceException(e.getLocalizedMessage(), e);
                }
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
