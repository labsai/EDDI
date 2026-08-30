/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.configuration;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.parser.IParserStore;
import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.datastore.IResourceStore;
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
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * Resolves {@code eddi://} references to the configurations they name.
 *
 * <h3>Reads bypass the authoring surface; writes do not</h3> This class serves
 * two populations that need opposite things from the same configurations.
 * <p>
 * {@link #getResource} is the <em>engine</em> resolving a reference in the
 * middle of a conversation turn: {@code LlmTask} loading its model config,
 * {@code ApiCallsTask} loading its api calls, {@code WorkflowTraversal} walking
 * a workflow. The identity on that thread is whoever is <em>chatting</em>, who
 * in general does not own — and must not need to own — the configuration the
 * agent is built from. So these reads go straight to the {@link IResourceStore}
 * beans, below any ownership enforcement.
 * <p>
 * {@link #duplicateResource} and {@link #deleteResource} are a <em>person</em>
 * editing: the cascade duplicate/delete behind {@code RestWorkflowStore} and
 * the orphan purge behind {@code RestOrphanAdmin}. Those keep going through the
 * {@code IRest*Store} facades, so {@code ResourceAccessGuard} sees them and a
 * caller cannot delete a resource they may not even read.
 * <p>
 * Before this split both went through the REST facades, which meant an
 * ownership check on the authoring surface would have been an ownership check
 * on every conversation turn — every shared agent would have failed to load its
 * own rule set. Keep the split: a read added here belongs on the store side, a
 * mutation on the facade side.
 *
 * @author ginccc
 */
@ApplicationScoped
public class ResourceClientLibrary implements IResourceClientLibrary {

    private final IParserStore parserStore;
    private final IDictionaryStore dictionaryStore;
    private final IRuleSetStore ruleSetStore;
    private final IApiCallsStore apiCallsStore;
    private final ILlmStore llmStore;
    private final IOutputStore outputStore;
    private final IPropertySetterStore propertySetterStore;
    private final IMcpCallsStore mcpCallsStore;
    private final IRagStore ragStore;

    private final IRestParserStore restParserStore;
    private final IRestDictionaryStore restDictionaryStore;
    private final IRestRuleSetStore restRuleSetStore;
    private final IRestApiCallsStore restApiCallsStore;
    private final IRestLlmStore restLlmStore;
    private final IRestOutputStore restOutputStore;
    private final IRestPropertySetterStore restPropertySetterStore;
    private final IRestMcpCallsStore restMcpCallsStore;
    private final IRestRagStore restRagStore;

    private Map<String, IResourceService> resourceServices;

    private static final Logger log = Logger.getLogger(ResourceClientLibrary.class);

    @Inject
    public ResourceClientLibrary(IParserStore parserStore, IDictionaryStore dictionaryStore, IRuleSetStore ruleSetStore,
            IApiCallsStore apiCallsStore, ILlmStore llmStore, IOutputStore outputStore, IPropertySetterStore propertySetterStore,
            IMcpCallsStore mcpCallsStore, IRagStore ragStore,
            IRestParserStore restParserStore, IRestDictionaryStore restDictionaryStore, IRestRuleSetStore restRuleSetStore,
            IRestApiCallsStore restApiCallsStore, IRestLlmStore restLlmStore, IRestOutputStore restOutputStore,
            IRestPropertySetterStore restPropertySetterStore, IRestMcpCallsStore restMcpCallsStore, IRestRagStore restRagStore) {
        this.parserStore = parserStore;
        this.dictionaryStore = dictionaryStore;
        this.ruleSetStore = ruleSetStore;
        this.apiCallsStore = apiCallsStore;
        this.llmStore = llmStore;
        this.outputStore = outputStore;
        this.propertySetterStore = propertySetterStore;
        this.mcpCallsStore = mcpCallsStore;
        this.ragStore = ragStore;

        this.restParserStore = restParserStore;
        this.restDictionaryStore = restDictionaryStore;
        this.restRuleSetStore = restRuleSetStore;
        this.restApiCallsStore = restApiCallsStore;
        this.restLlmStore = restLlmStore;
        this.restOutputStore = restOutputStore;
        this.restPropertySetterStore = restPropertySetterStore;
        this.restMcpCallsStore = restMcpCallsStore;
        this.restRagStore = restRagStore;

        init();
    }

    @Override
    public void init() throws ResourceClientLibraryException {
        this.resourceServices = new HashMap<>();

        resourceServices.put("ai.labs.parser", new IResourceService() {
            @Override
            public Object read(String id, Integer version) {
                return readFromStore(parserStore, id, version);
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

        resourceServices.put("ai.labs.regulardictionary", new IResourceService() {
            @Override
            public Object read(String id, Integer version) {
                return readFromStore(dictionaryStore, id, version);
            }

            @Override
            public Response duplicate(String id, Integer version) {
                return restDictionaryStore.duplicateRegularDictionary(id, version);
            }

            @Override
            public Response delete(String id, Integer version, boolean permanent) {
                return restDictionaryStore.deleteRegularDictionary(id, version, permanent);
            }
        });

        // Alias: IRestDictionaryStore uses resourceBaseType "ai.labs.dictionary"
        resourceServices.put("ai.labs.dictionary", resourceServices.get("ai.labs.regulardictionary"));

        resourceServices.put("ai.labs.behavior", new IResourceService() {
            @Override
            public Object read(String id, Integer version) {
                return readFromStore(ruleSetStore, id, version);
            }

            @Override
            public Response duplicate(String id, Integer version) {
                return restRuleSetStore.duplicateRuleSet(id, version);
            }

            @Override
            public Response delete(String id, Integer version, boolean permanent) {
                return restRuleSetStore.deleteRuleSet(id, version, permanent);
            }
        });

        // Alias: IRestRuleSetStore uses resourceBaseType "ai.labs.rules"
        resourceServices.put("ai.labs.rules", resourceServices.get("ai.labs.behavior"));

        resourceServices.put("ai.labs.httpcalls", new IResourceService() {
            @Override
            public Object read(String id, Integer version) {
                return readFromStore(apiCallsStore, id, version);
            }

            @Override
            public Response duplicate(String id, Integer version) {
                return restApiCallsStore.duplicateApiCalls(id, version);
            }

            @Override
            public Response delete(String id, Integer version, boolean permanent) {
                return restApiCallsStore.deleteApiCalls(id, version, permanent);
            }
        });

        // Alias: IRestApiCallsStore uses resourceBaseType "ai.labs.apicalls"
        resourceServices.put("ai.labs.apicalls", resourceServices.get("ai.labs.httpcalls"));

        resourceServices.put("ai.labs.llm", new IResourceService() {
            @Override
            public Object read(String id, Integer version) {
                return readFromStore(llmStore, id, version);
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

        resourceServices.put("ai.labs.output", new IResourceService() {
            @Override
            public Object read(String id, Integer version) {
                // The 6-arg read is IOutputStore's own; the empty filter/order and
                // zero index/limit reproduce exactly what the REST facade passed.
                try {
                    return outputStore.read(id, version, "", "", 0, 0);
                } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
                    throw sneakyThrow(e);
                }
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

        resourceServices.put("ai.labs.property", new IResourceService() {
            @Override
            public Object read(String id, Integer version) {
                return readFromStore(propertySetterStore, id, version);
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

        resourceServices.put("ai.labs.mcpcalls", new IResourceService() {
            @Override
            public Object read(String id, Integer version) {
                return readFromStore(mcpCallsStore, id, version);
            }

            @Override
            public Response duplicate(String id, Integer version) {
                return restMcpCallsStore.duplicateMcpCalls(id, version);
            }

            @Override
            public Response delete(String id, Integer version, boolean permanent) {
                return restMcpCallsStore.deleteMcpCalls(id, version, permanent);
            }
        });

        resourceServices.put("ai.labs.rag", new IResourceService() {
            @Override
            public Object read(String id, Integer version) {
                return readFromStore(ragStore, id, version);
            }

            @Override
            public Response duplicate(String id, Integer version) {
                return restRagStore.duplicateRag(id, version);
            }

            @Override
            public Response delete(String id, Integer version, boolean permanent) {
                return restRagStore.deleteRag(id, version, permanent);
            }
        });
    }

    /**
     * Reads through {@link IResourceStore}, rethrowing its checked exceptions the
     * same way the REST facades did.
     * <p>
     * The facades wrapped every store exception with
     * {@code SneakyThrow.sneakyThrow}, so callers of {@link #getResource} already
     * see {@code ResourceNotFoundException} undeclared. Preserving that keeps the
     * behaviour of every pipeline task unchanged by this refactor — most notably
     * {@code WorkflowTraversal}, which catches {@code Exception} and treats a
     * missing reference as a skipped step.
     */
    private static <T> T readFromStore(IResourceStore<T> store, String id, Integer version) {
        try {
            return store.read(id, version);
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getResource(URI uri, Class<T> clazz) throws ServiceException {
        String type = uri.getHost();
        IResourceService proxy = resourceServices.get(type);

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
        IResourceService proxy = resourceServices.get(type);
        if (RuntimeUtilities.isNullOrEmpty(proxy)) {
            throw new ServiceException(String.format("Could not find proxy for type '%s' in uri '%s'", type, uri));
        }

        IResourceId resourceId = RestUtilities.extractResourceId(uri);
        return proxy.duplicate(resourceId.getId(), resourceId.getVersion());
    }

    @Override
    public Response deleteResource(URI uri, boolean permanent) throws ServiceException {
        String type = uri.getHost();
        IResourceService proxy = resourceServices.get(type);
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
