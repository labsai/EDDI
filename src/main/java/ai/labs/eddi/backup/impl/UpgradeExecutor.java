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
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
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

    @Inject
    public UpgradeExecutor(IRestAgentStore agentStore,
            IRestWorkflowStore workflowStore,
            IRestInterfaceFactory restInterfaceFactory,
            IRestPromptSnippetStore snippetStore,
            IJsonSerialization jsonSerialization,
            StructuralMatcher structuralMatcher) {
        this.agentStore = agentStore;
        this.workflowStore = workflowStore;
        this.restInterfaceFactory = restInterfaceFactory;
        this.snippetStore = snippetStore;
        this.jsonSerialization = jsonSerialization;
        this.structuralMatcher = structuralMatcher;
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

            AgentSourceData sourceAgent = source.readAgent();
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

    // ==================== Extension Update Dispatch ====================

    /**
     * Updates a target extension resource with content from the source. Dispatches
     * to the correct store based on the extension type.
     */
    private URI updateExtension(ExtensionSourceData source, String targetId, Integer targetVersion) {
        try {
            return switch (source.type()) {
                case "regulardictionary" -> {
                    var config = jsonSerialization.deserialize(source.contentJson(), DictionaryConfiguration.class);
                    var store = getStore(IRestDictionaryStore.class);
                    Response resp = store.updateRegularDictionary(targetId, targetVersion, config);
                    yield resp.getStatus() == 200
                            ? URI.create(IRestDictionaryStore.resourceURI + targetId + IRestDictionaryStore.versionQueryParam + (targetVersion + 1))
                            : null;
                }
                case "behavior" -> {
                    var config = jsonSerialization.deserialize(source.contentJson(), RuleSetConfiguration.class);
                    var store = getStore(IRestRuleSetStore.class);
                    Response resp = store.updateRuleSet(targetId, targetVersion, config);
                    yield resp.getStatus() == 200
                            ? URI.create(IRestRuleSetStore.resourceURI + targetId + IRestRuleSetStore.versionQueryParam + (targetVersion + 1))
                            : null;
                }
                case "httpcalls" -> {
                    var config = jsonSerialization.deserialize(source.contentJson(), ApiCallsConfiguration.class);
                    var store = getStore(IRestApiCallsStore.class);
                    Response resp = store.updateApiCalls(targetId, targetVersion, config);
                    yield resp.getStatus() == 200
                            ? URI.create(IRestApiCallsStore.resourceURI + targetId + IRestApiCallsStore.versionQueryParam + (targetVersion + 1))
                            : null;
                }
                case "langchain" -> {
                    var config = jsonSerialization.deserialize(source.contentJson(), LlmConfiguration.class);
                    var store = getStore(IRestLlmStore.class);
                    Response resp = store.updateLlm(targetId, targetVersion, config);
                    yield resp.getStatus() == 200
                            ? URI.create(IRestLlmStore.resourceURI + targetId + IRestLlmStore.versionQueryParam + (targetVersion + 1))
                            : null;
                }
                case "property" -> {
                    var config = jsonSerialization.deserialize(source.contentJson(), PropertySetterConfiguration.class);
                    var store = getStore(IRestPropertySetterStore.class);
                    Response resp = store.updatePropertySetter(targetId, targetVersion, config);
                    yield resp.getStatus() == 200
                            ? URI.create(IRestPropertySetterStore.resourceURI + targetId + IRestPropertySetterStore.versionQueryParam
                                    + (targetVersion + 1))
                            : null;
                }
                case "output" -> {
                    var config = jsonSerialization.deserialize(source.contentJson(), OutputConfigurationSet.class);
                    var store = getStore(IRestOutputStore.class);
                    Response resp = store.updateOutputSet(targetId, targetVersion, config);
                    yield resp.getStatus() == 200
                            ? URI.create(IRestOutputStore.resourceURI + targetId + IRestOutputStore.versionQueryParam + (targetVersion + 1))
                            : null;
                }
                case "mcpcalls" -> {
                    var config = jsonSerialization.deserialize(source.contentJson(), McpCallsConfiguration.class);
                    var store = getStore(IRestMcpCallsStore.class);
                    Response resp = store.updateMcpCalls(targetId, targetVersion, config);
                    yield resp.getStatus() == 200
                            ? URI.create(IRestMcpCallsStore.resourceURI + targetId + IRestMcpCallsStore.versionQueryParam + (targetVersion + 1))
                            : null;
                }
                case "rag" -> {
                    var config = jsonSerialization.deserialize(source.contentJson(), RagConfiguration.class);
                    var store = getStore(IRestRagStore.class);
                    Response resp = store.updateRag(targetId, targetVersion, config);
                    yield resp.getStatus() == 200
                            ? URI.create(IRestRagStore.resourceURI + targetId + IRestRagStore.versionQueryParam + (targetVersion + 1))
                            : null;
                }
                default -> {
                    log.warnf("Unknown extension type: %s", source.type());
                    yield null;
                }
            };
        } catch (Exception e) {
            log.warnf("Failed to update %s '%s' (target=%s): %s",
                    source.type(), source.name(), targetId, e.getMessage());
            return null;
        }
    }

    /**
     * Creates a new extension resource from the source content.
     */
    private URI createExtension(ExtensionSourceData source) {
        try {
            Response resp = switch (source.type()) {
                case "regulardictionary" -> {
                    var config = jsonSerialization.deserialize(source.contentJson(), DictionaryConfiguration.class);
                    yield getStore(IRestDictionaryStore.class).createRegularDictionary(config);
                }
                case "behavior" -> {
                    var config = jsonSerialization.deserialize(source.contentJson(), RuleSetConfiguration.class);
                    yield getStore(IRestRuleSetStore.class).createRuleSet(config);
                }
                case "httpcalls" -> {
                    var config = jsonSerialization.deserialize(source.contentJson(), ApiCallsConfiguration.class);
                    yield getStore(IRestApiCallsStore.class).createApiCalls(config);
                }
                case "langchain" -> {
                    var config = jsonSerialization.deserialize(source.contentJson(), LlmConfiguration.class);
                    yield getStore(IRestLlmStore.class).createLlm(config);
                }
                case "property" -> {
                    var config = jsonSerialization.deserialize(source.contentJson(), PropertySetterConfiguration.class);
                    yield getStore(IRestPropertySetterStore.class).createPropertySetter(config);
                }
                case "output" -> {
                    var config = jsonSerialization.deserialize(source.contentJson(), OutputConfigurationSet.class);
                    yield getStore(IRestOutputStore.class).createOutputSet(config);
                }
                case "mcpcalls" -> {
                    var config = jsonSerialization.deserialize(source.contentJson(), McpCallsConfiguration.class);
                    yield getStore(IRestMcpCallsStore.class).createMcpCalls(config);
                }
                case "rag" -> {
                    var config = jsonSerialization.deserialize(source.contentJson(), RagConfiguration.class);
                    yield getStore(IRestRagStore.class).createRag(config);
                }
                default -> {
                    log.warnf("Unknown extension type for create: %s", source.type());
                    yield null;
                }
            };
            return resp != null ? resp.getLocation() : null;
        } catch (Exception e) {
            log.warnf("Failed to create %s '%s': %s", source.type(), source.name(), e.getMessage());
            return null;
        }
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
     * Updates the agent configuration: - Replaces workflow URIs with updated
     * versions - Appends new workflows at specified positions
     */
    private URI updateAgentConfig(String agentId,
                                  Map<String, URI> updatedWorkflowUris,
                                  List<URI> newWorkflowUris,
                                  List<String> workflowOrder) {
        try {
            // Read current agent config
            IResourceId agentResId = RestUtilities.extractResourceId(
                    URI.create(IRestAgentStore.resourceURI + agentId + "?version=1"));
            int currentVersion = 1; // will be overridden
            try {
                var descriptors = agentStore.readAgentDescriptors("", 0, 100);
                for (var desc : descriptors) {
                    IResourceId resId = RestUtilities.extractResourceId(desc.getResource());
                    if (resId != null && resId.getId().equals(agentId)) {
                        currentVersion = resId.getVersion();
                        break;
                    }
                }
            } catch (Exception e) {
                log.debugf("Could not find latest version for agent %s, using 1", agentId);
            }

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
     * Reorders workflows according to the specified order. Workflow IDs in
     * workflowOrder are extracted and matched against the existing workflow URIs.
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
