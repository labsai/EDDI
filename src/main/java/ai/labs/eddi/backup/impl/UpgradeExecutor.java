package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource;
import ai.labs.eddi.backup.IResourceSource.*;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Executes an upgrade by syncing content from a source into an existing target
 * agent. For each selected resource in the preview, writes the source content
 * into the target's existing resource (creating a new version).
 * <p>
 * The target agent's URI structure stays unchanged — only content is updated
 * and version numbers incremented.
 * <p>
 * Extension type dispatch is handled via {@link ExtensionStoreRegistry} to
 * avoid duplicated switch blocks.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class UpgradeExecutor {

    private static final Logger log = Logger.getLogger(UpgradeExecutor.class);

    private final IRestAgentStore agentStore;
    private final IRestWorkflowStore workflowStore;
    private final IRestInterfaceFactory restInterfaceFactory;
    private final IRestPromptSnippetStore snippetStore;
    private final IJsonSerialization jsonSerialization;
    private final StructuralMatcher structuralMatcher;
    private final IDocumentDescriptorStore documentDescriptorStore;

    @Inject
    public UpgradeExecutor(IRestAgentStore agentStore,
            IRestWorkflowStore workflowStore,
            IRestInterfaceFactory restInterfaceFactory,
            IRestPromptSnippetStore snippetStore,
            IJsonSerialization jsonSerialization,
            StructuralMatcher structuralMatcher,
            IDocumentDescriptorStore documentDescriptorStore) {
        this.agentStore = agentStore;
        this.workflowStore = workflowStore;
        this.restInterfaceFactory = restInterfaceFactory;
        this.snippetStore = snippetStore;
        this.jsonSerialization = jsonSerialization;
        this.structuralMatcher = structuralMatcher;
        this.documentDescriptorStore = documentDescriptorStore;
    }

    /**
     * Execute an upgrade of the target agent with content from the source.
     *
     * @param source
     *            the resource source (ZIP, remote API, local)
     * @param targetAgentId
     *            the local agent to upgrade
     * @param selectedSourceIds
     *            source resource IDs to process (null = all)
     * @param workflowOrder
     *            desired final workflow order (null = append new ones at end)
     * @return URI of the updated agent
     */
    public URI executeUpgrade(IResourceSource source,
                              String targetAgentId,
                              Set<String> selectedSourceIds,
                              List<String> workflowOrder) {
        try {
            // 1. Build the preview to get the match map
            ImportPreview preview = structuralMatcher.buildPreview(source, targetAgentId, false);

            List<WorkflowSourceData> sourceWorkflows = source.readWorkflows();
            List<SnippetSourceData> sourceSnippets = source.readSnippets();

            // Build a lookup: sourceId → ResourceDiff
            Map<String, ResourceDiff> diffMap = preview.resources().stream()
                    .collect(Collectors.toMap(ResourceDiff::sourceId, d -> d, (a, b) -> a));

            // 2. Process snippets first (they must exist before other resources reference
            // them)
            for (SnippetSourceData snippet : sourceSnippets) {
                ResourceDiff diff = diffMap.get(snippet.sourceId());
                if (diff == null || !isSelected(selectedSourceIds, snippet.sourceId()))
                    continue;
                processSnippet(snippet, diff);
            }

            // 3. Process each workflow's extensions
            // Track workflow URI updates: targetWorkflowId → new version URI
            Map<String, URI> updatedWorkflowUris = new LinkedHashMap<>();
            List<URI> newWorkflowUris = new ArrayList<>();

            for (WorkflowSourceData sourceWf : sourceWorkflows) {
                ResourceDiff wfDiff = diffMap.get(sourceWf.sourceId());
                if (wfDiff == null)
                    continue;

                if (wfDiff.action() == DiffAction.CREATE) {
                    // New workflow — create it if selected
                    if (isSelected(selectedSourceIds, sourceWf.sourceId())) {
                        URI newUri = createNewWorkflow(sourceWf);
                        if (newUri != null) {
                            newWorkflowUris.add(newUri);
                        }
                    }
                } else if (wfDiff.action() == DiffAction.UPDATE) {
                    // Matched workflow — process extensions
                    Map<String, URI> extensionUpdates = processWorkflowExtensions(
                            sourceWf, diffMap, selectedSourceIds,
                            wfDiff.targetId(), wfDiff.targetVersion());

                    // Update the workflow config with new extension version URIs
                    if (!extensionUpdates.isEmpty()) {
                        URI updatedUri = updateWorkflowExtensionUris(
                                wfDiff.targetId(), wfDiff.targetVersion(), extensionUpdates);
                        if (updatedUri != null) {
                            updatedWorkflowUris.put(wfDiff.targetId(), updatedUri);
                        }
                    }
                }
            }

            // 4. Update the agent config with new workflow version URIs
            return updateAgentConfig(targetAgentId, updatedWorkflowUris,
                    newWorkflowUris, workflowOrder);

        } catch (Exception e) {
            log.errorf("Upgrade failed for target agent %s: %s", targetAgentId, e.getMessage());
            throw new RuntimeException("Upgrade failed: " + e.getMessage(), e);
        }
    }

    // ==================== Snippet Processing ====================

    private void processSnippet(SnippetSourceData sourceSnippet, ResourceDiff diff) {
        try {
            if (diff.action() == DiffAction.UPDATE && diff.targetId() != null) {
                // Update existing snippet
                snippetStore.updateSnippet(diff.targetId(), diff.targetVersion(), sourceSnippet.snippet());
                log.infof("Updated snippet '%s' (target=%s, v%d→v%d)",
                        sourceSnippet.name(), diff.targetId(), diff.targetVersion(), diff.targetVersion() + 1);
            } else if (diff.action() == DiffAction.CREATE) {
                // Create new snippet
                snippetStore.createSnippet(sourceSnippet.snippet());
                log.infof("Created snippet '%s'", sourceSnippet.name());
            }
        } catch (Exception e) {
            log.warnf("Failed to process snippet '%s': %s", sourceSnippet.name(), e.getMessage());
        }
    }

    // ==================== Workflow Extension Processing ====================

    /**
     * For each extension in a matched workflow, update the target extension with
     * the source content.
     *
     * @return map of stepType → updated extension URI (with new version)
     */
    private Map<String, URI> processWorkflowExtensions(
                                                       WorkflowSourceData sourceWf,
                                                       Map<String, ResourceDiff> diffMap,
                                                       Set<String> selectedSourceIds,
                                                       String targetWorkflowId,
                                                       Integer targetWorkflowVersion) {

        Map<String, URI> updates = new LinkedHashMap<>();

        for (Map.Entry<String, ExtensionSourceData> entry : sourceWf.extensions().entrySet()) {
            String stepType = entry.getKey();
            ExtensionSourceData sourceExt = entry.getValue();
            ResourceDiff extDiff = diffMap.get(sourceExt.sourceId());

            if (extDiff == null || extDiff.action() == DiffAction.SKIP)
                continue;
            if (!isSelected(selectedSourceIds, sourceExt.sourceId()))
                continue;

            try {
                if (extDiff.action() == DiffAction.UPDATE && extDiff.targetId() != null) {
                    URI updatedUri = updateExtension(sourceExt, extDiff.targetId(), extDiff.targetVersion());
                    if (updatedUri != null) {
                        updates.put(stepType, updatedUri);
                        log.infof("Updated %s '%s' (target=%s, v%d→v%d)",
                                sourceExt.type(), sourceExt.name(),
                                extDiff.targetId(), extDiff.targetVersion(), extDiff.targetVersion() + 1);
                    }
                } else if (extDiff.action() == DiffAction.CREATE) {
                    URI newUri = createExtension(sourceExt);
                    if (newUri != null) {
                        updates.put(stepType, newUri);
                        log.infof("Created %s '%s'", sourceExt.type(), sourceExt.name());
                    }
                }
            } catch (Exception e) {
                log.warnf("Failed to process extension %s '%s': %s",
                        sourceExt.type(), sourceExt.name(), e.getMessage());
            }
        }

        return updates;
    }

    // ==================== Extension Store Registry ====================

    /**
     * Central registry mapping extension type names to their configuration class
     * and store operations. Adding a new extension type requires only one new entry
     * here — no switch blocks to maintain.
     */
    @SuppressWarnings("unchecked")
    private <T> ExtensionStoreOps<T> resolveExtensionOps(String extensionType) throws RestInterfaceFactory.RestInterfaceFactoryException {
        return (ExtensionStoreOps<T>) switch (extensionType) {
            case "regulardictionary" -> new ExtensionStoreOps<>(
                    DictionaryConfiguration.class,
                    getStore(IRestDictionaryStore.class),
                    IRestDictionaryStore.resourceURI,
                    IRestDictionaryStore.versionQueryParam);
            case "behavior" -> new ExtensionStoreOps<>(
                    RuleSetConfiguration.class,
                    getStore(IRestRuleSetStore.class),
                    IRestRuleSetStore.resourceURI,
                    IRestRuleSetStore.versionQueryParam);
            case "httpcalls" -> new ExtensionStoreOps<>(
                    ApiCallsConfiguration.class,
                    getStore(IRestApiCallsStore.class),
                    IRestApiCallsStore.resourceURI,
                    IRestApiCallsStore.versionQueryParam);
            case "langchain" -> new ExtensionStoreOps<>(
                    LlmConfiguration.class,
                    getStore(IRestLlmStore.class),
                    IRestLlmStore.resourceURI,
                    IRestLlmStore.versionQueryParam);
            case "property" -> new ExtensionStoreOps<>(
                    PropertySetterConfiguration.class,
                    getStore(IRestPropertySetterStore.class),
                    IRestPropertySetterStore.resourceURI,
                    IRestPropertySetterStore.versionQueryParam);
            case "output" -> new ExtensionStoreOps<>(
                    OutputConfigurationSet.class,
                    getStore(IRestOutputStore.class),
                    IRestOutputStore.resourceURI,
                    IRestOutputStore.versionQueryParam);
            case "mcpcalls" -> new ExtensionStoreOps<>(
                    McpCallsConfiguration.class,
                    getStore(IRestMcpCallsStore.class),
                    IRestMcpCallsStore.resourceURI,
                    IRestMcpCallsStore.versionQueryParam);
            case "rag" -> new ExtensionStoreOps<>(
                    RagConfiguration.class,
                    getStore(IRestRagStore.class),
                    IRestRagStore.resourceURI,
                    IRestRagStore.versionQueryParam);
            default -> null;
        };
    }

    /**
     * Holds the configuration class, store reference, and URI pattern for a single
     * extension type. Used by {@link #updateExtension} and {@link #createExtension}
     * to eliminate duplicated dispatch logic.
     */
    private record ExtensionStoreOps<T>(
            Class<T> configClass,
            Object store,
            String resourceUri,
            String versionQueryParam) {
    }

    // ==================== Extension Update/Create (Unified) ====================

    /**
     * Updates a target extension resource with content from the source. Dispatches
     * to the correct store via {@link #resolveExtensionOps}.
     */
    private URI updateExtension(ExtensionSourceData source, String targetId, Integer targetVersion) {
        try {
            ExtensionStoreOps<?> ops = resolveExtensionOps(source.type());
            if (ops == null) {
                log.warnf("Unknown extension type: %s", source.type());
                return null;
            }
            Response resp = dispatchUpdate(ops, source.contentJson(), targetId, targetVersion);
            return resp != null && resp.getStatus() == 200
                    ? URI.create(ops.resourceUri() + targetId + ops.versionQueryParam() + (targetVersion + 1))
                    : null;
        } catch (Exception e) {
            log.warnf("Failed to update %s '%s' (target=%s): %s",
                    source.type(), source.name(), targetId, e.getMessage());
            return null;
        }
    }

    /**
     * Creates a new extension resource from the source content. Dispatches to the
     * correct store via {@link #resolveExtensionOps}.
     */
    private URI createExtension(ExtensionSourceData source) {
        try {
            ExtensionStoreOps<?> ops = resolveExtensionOps(source.type());
            if (ops == null) {
                log.warnf("Unknown extension type for create: %s", source.type());
                return null;
            }
            Response resp = dispatchCreate(ops, source.contentJson());
            return resp != null ? resp.getLocation() : null;
        } catch (Exception e) {
            log.warnf("Failed to create %s '%s': %s", source.type(), source.name(), e.getMessage());
            return null;
        }
    }

    /**
     * Deserializes JSON and calls the store's update method via reflection-free
     * type-safe dispatch.
     */
    @SuppressWarnings("unchecked")
    private <T> Response dispatchUpdate(ExtensionStoreOps<T> ops, String json,
                                        String targetId, Integer targetVersion)
            throws Exception {
        T config = jsonSerialization.deserialize(json, ops.configClass());
        Object store = ops.store();
        // Type-safe dispatch — each store has a different update method name
        return switch (ops.configClass().getSimpleName()) {
            case "DictionaryConfiguration" ->
                ((IRestDictionaryStore) store).updateRegularDictionary(targetId, targetVersion, (DictionaryConfiguration) config);
            case "RuleSetConfiguration" -> ((IRestRuleSetStore) store).updateRuleSet(targetId, targetVersion, (RuleSetConfiguration) config);
            case "ApiCallsConfiguration" -> ((IRestApiCallsStore) store).updateApiCalls(targetId, targetVersion, (ApiCallsConfiguration) config);
            case "LlmConfiguration" -> ((IRestLlmStore) store).updateLlm(targetId, targetVersion, (LlmConfiguration) config);
            case "PropertySetterConfiguration" ->
                ((IRestPropertySetterStore) store).updatePropertySetter(targetId, targetVersion, (PropertySetterConfiguration) config);
            case "OutputConfigurationSet" -> ((IRestOutputStore) store).updateOutputSet(targetId, targetVersion, (OutputConfigurationSet) config);
            case "McpCallsConfiguration" -> ((IRestMcpCallsStore) store).updateMcpCalls(targetId, targetVersion, (McpCallsConfiguration) config);
            case "RagConfiguration" -> ((IRestRagStore) store).updateRag(targetId, targetVersion, (RagConfiguration) config);
            default -> throw new IllegalArgumentException("Unsupported config class: " + ops.configClass().getSimpleName());
        };
    }

    /**
     * Deserializes JSON and calls the store's create method via reflection-free
     * type-safe dispatch.
     */
    @SuppressWarnings("unchecked")
    private <T> Response dispatchCreate(ExtensionStoreOps<T> ops, String json) throws Exception {
        T config = jsonSerialization.deserialize(json, ops.configClass());
        Object store = ops.store();
        return switch (ops.configClass().getSimpleName()) {
            case "DictionaryConfiguration" -> ((IRestDictionaryStore) store).createRegularDictionary((DictionaryConfiguration) config);
            case "RuleSetConfiguration" -> ((IRestRuleSetStore) store).createRuleSet((RuleSetConfiguration) config);
            case "ApiCallsConfiguration" -> ((IRestApiCallsStore) store).createApiCalls((ApiCallsConfiguration) config);
            case "LlmConfiguration" -> ((IRestLlmStore) store).createLlm((LlmConfiguration) config);
            case "PropertySetterConfiguration" -> ((IRestPropertySetterStore) store).createPropertySetter((PropertySetterConfiguration) config);
            case "OutputConfigurationSet" -> ((IRestOutputStore) store).createOutputSet((OutputConfigurationSet) config);
            case "McpCallsConfiguration" -> ((IRestMcpCallsStore) store).createMcpCalls((McpCallsConfiguration) config);
            case "RagConfiguration" -> ((IRestRagStore) store).createRag((RagConfiguration) config);
            default -> throw new IllegalArgumentException("Unsupported config class: " + ops.configClass().getSimpleName());
        };
    }

    // ==================== Workflow Updates ====================

    private URI createNewWorkflow(WorkflowSourceData sourceWf) {
        try {
            Response resp = workflowStore.createWorkflow(sourceWf.config());
            return resp != null ? resp.getLocation() : null;
        } catch (Exception e) {
            log.warnf("Failed to create workflow '%s': %s", sourceWf.name(), e.getMessage());
            return null;
        }
    }

    /**
     * Updates the extension URIs within a target workflow's config. When an
     * extension was updated (version incremented), the workflow config needs to
     * point to the new version.
     */
    private URI updateWorkflowExtensionUris(String workflowId, Integer workflowVersion,
                                            Map<String, URI> extensionUpdates) {
        try {
            WorkflowConfiguration wfConfig = workflowStore.readWorkflow(workflowId, workflowVersion);

            boolean changed = false;
            for (WorkflowConfiguration.WorkflowStep step : wfConfig.getWorkflowSteps()) {
                if (step.getType() == null)
                    continue;
                String stepType = step.getType().toString();
                URI newExtUri = extensionUpdates.get(stepType);
                if (newExtUri != null) {
                    step.getExtensions().put("uri", newExtUri.toString());
                    changed = true;
                }
            }

            if (changed) {
                Response resp = workflowStore.updateWorkflow(workflowId, workflowVersion, wfConfig);
                if (resp.getStatus() == 200) {
                    return URI.create(IRestWorkflowStore.resourceURI + workflowId
                            + IRestWorkflowStore.versionQueryParam + (workflowVersion + 1));
                }
            }

            return null;
        } catch (Exception e) {
            log.warnf("Failed to update workflow URIs %s: %s", workflowId, e.getMessage());
            return null;
        }
    }

    // ==================== Agent Config Update ====================

    /**
     * Updates the agent configuration:
     * <ul>
     * <li>Replaces workflow URIs with updated versions</li>
     * <li>Appends new workflows at specified positions</li>
     * <li>Applies custom workflow order if specified</li>
     * </ul>
     */
    private URI updateAgentConfig(String agentId,
                                  Map<String, URI> updatedWorkflowUris,
                                  List<URI> newWorkflowUris,
                                  List<String> workflowOrder) {
        try {
            int currentVersion = readLatestVersion(agentId);
            AgentConfiguration agentConfig = agentStore.readAgent(agentId, currentVersion);

            // Replace workflow URIs with updated versions
            List<URI> workflows = new ArrayList<>(agentConfig.getWorkflows());
            for (int i = 0; i < workflows.size(); i++) {
                IResourceId wfResId = RestUtilities.extractResourceId(workflows.get(i));
                if (wfResId != null && updatedWorkflowUris.containsKey(wfResId.getId())) {
                    workflows.set(i, updatedWorkflowUris.get(wfResId.getId()));
                }
            }

            // Append new workflows
            workflows.addAll(newWorkflowUris);

            // Apply custom workflow order if specified
            if (workflowOrder != null && !workflowOrder.isEmpty()) {
                workflows = reorderWorkflows(workflows, workflowOrder);
            }

            agentConfig.setWorkflows(workflows);

            // Update the agent
            Response resp = agentStore.updateAgent(agentId, currentVersion, agentConfig);
            if (resp.getStatus() == 200) {
                URI updatedUri = URI.create(IRestAgentStore.resourceURI + agentId + "?version=" + (currentVersion + 1));
                log.infof("Agent '%s' upgraded successfully (v%d→v%d)", agentId, currentVersion, currentVersion + 1);
                return updatedUri;
            }

            return null;
        } catch (Exception e) {
            log.errorf("Failed to update agent config %s: %s", agentId, e.getMessage());
            throw new RuntimeException("Agent config update failed: " + e.getMessage(), e);
        }
    }

    /**
     * Reads the latest version of a resource via its descriptor.
     */
    private int readLatestVersion(String resourceId) {
        try {
            DocumentDescriptor desc = documentDescriptorStore.readDescriptor(resourceId, null);
            if (desc != null && desc.getResource() != null) {
                IResourceId resId = RestUtilities.extractResourceId(desc.getResource());
                if (resId != null)
                    return resId.getVersion();
            }
        } catch (Exception e) {
            log.debugf("Could not find latest version for %s, using 1", resourceId);
        }
        return 1;
    }

    /**
     * Reorders workflows according to the specified order. Workflow IDs in
     * workflowOrder are extracted and matched against the existing workflow URIs.
     * Workflows not mentioned in the order are appended at the end.
     */
    private List<URI> reorderWorkflows(List<URI> workflows, List<String> workflowOrder) {
        Map<String, URI> uriById = new LinkedHashMap<>();
        for (URI uri : workflows) {
            IResourceId resId = RestUtilities.extractResourceId(uri);
            if (resId != null) {
                uriById.put(resId.getId(), uri);
            }
        }

        List<URI> ordered = new ArrayList<>();
        // First, add in specified order
        for (String id : workflowOrder) {
            URI uri = uriById.remove(id);
            if (uri != null) {
                ordered.add(uri);
            }
        }
        // Then append any remaining (not mentioned in order)
        ordered.addAll(uriById.values());

        return ordered;
    }

    // ==================== Utilities ====================

    private boolean isSelected(Set<String> selectedSourceIds, String sourceId) {
        return selectedSourceIds == null || selectedSourceIds.contains(sourceId);
    }

    private <T> T getStore(Class<T> clazz) throws RestInterfaceFactory.RestInterfaceFactoryException {
        return restInterfaceFactory.get(clazz);
    }
}
