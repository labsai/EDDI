/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.backup.IResourceSource;
import ai.labs.eddi.backup.IResourceSource.*;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.backup.model.UpgradeResult;
import ai.labs.eddi.backup.model.UpgradeResult.ResourceFailure;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import static ai.labs.eddi.configs.descriptors.ResourceUtilities.createDocumentDescriptor;

/**
 * Executes an upgrade by syncing content from a source into an existing target
 * agent. For each selected resource in the preview, writes the source content
 * into the target's existing resource (creating a new version).
 * <p>
 * The target agent's URI structure stays unchanged — only content is updated
 * and version numbers incremented.
 * <p>
 * Adding an extension type takes two edits: register the resource authority in
 * {@link WorkflowExtensions} (file extension + remote REST path) and add its
 * store operations to {@link #resolveExtensionOps(String)}.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class UpgradeExecutor {

    private static final Logger LOGGER = Logger.getLogger(UpgradeExecutor.class);

    private final IRestAgentStore agentStore;
    private final IRestWorkflowStore workflowStore;
    private final IRestPromptSnippetStore snippetStore;
    private final IJsonSerialization jsonSerialization;
    private final StructuralMatcher structuralMatcher;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final BackupMetrics metrics;

    private final ResourceAccessGuard resourceAccessGuard;

    @Inject
    public UpgradeExecutor(IRestAgentStore agentStore,
            IRestWorkflowStore workflowStore,
            IRestPromptSnippetStore snippetStore,
            IJsonSerialization jsonSerialization,
            StructuralMatcher structuralMatcher,
            IDocumentDescriptorStore documentDescriptorStore,
            BackupMetrics metrics,
            ResourceAccessGuard resourceAccessGuard) {
        this.metrics = metrics;
        this.resourceAccessGuard = resourceAccessGuard;
        this.agentStore = agentStore;
        this.workflowStore = workflowStore;
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
     * @return what actually happened, per resource — see {@link UpgradeResult}
     */
    public UpgradeResult executeUpgrade(IResourceSource source,
                                        String targetAgentId,
                                        Set<String> selectedSourceIds,
                                        List<String> workflowOrder) {
        var outcome = new Outcome();
        metrics.upgradeAttempted();
        try {
            // 1. Build the preview to get the match map. Content is requested because
            // the target's current JSON is needed to put back values the export's
            // secret scrubber replaced with ${vault:REDACTED} — see
            // restoreRedactedSecrets.
            ImportPreview preview = structuralMatcher.buildPreview(source, targetAgentId, true);

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
                processSnippet(snippet, diff, outcome);
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
                        URI newUri = createNewWorkflow(sourceWf, outcome);
                        if (newUri != null) {
                            newWorkflowUris.add(newUri);
                            outcome.created++;
                        }
                    }
                } else if (wfDiff.action() == DiffAction.UPDATE) {
                    // Matched workflow — process extensions
                    Map<String, URI> extensionUpdates = processWorkflowExtensions(
                            sourceWf, diffMap, selectedSourceIds, outcome);

                    // Update the workflow config with new extension version URIs
                    if (!extensionUpdates.isEmpty()) {
                        URI updatedUri = updateWorkflowExtensionUris(
                                wfDiff.targetId(), wfDiff.targetVersion(), extensionUpdates, outcome);
                        if (updatedUri != null) {
                            updatedWorkflowUris.put(wfDiff.targetId(), updatedUri);
                            outcome.updated++;
                        }
                    }
                } else {
                    outcome.skipped++;
                }
            }

            // 4. Update the agent config with new workflow version URIs — but only
            // when there is something to write. An unconditional update wrote a
            // byte-identical agent configuration and bumped its version, so a CI job
            // that syncs on every build inflated the version history forever and
            // 'v14' said nothing about whether anything had changed.
            boolean agentNeedsUpdate = !updatedWorkflowUris.isEmpty()
                    || !newWorkflowUris.isEmpty()
                    || (workflowOrder != null && !workflowOrder.isEmpty());

            URI agentUri = agentNeedsUpdate
                    ? updateAgentConfig(targetAgentId, updatedWorkflowUris, newWorkflowUris, workflowOrder)
                    : currentAgentUri(targetAgentId);

            if (!agentNeedsUpdate) {
                LOGGER.infof("Agent '%s' upgrade wrote no workflow changes — agent version left at %s",
                        targetAgentId, agentUri);
            }

            UpgradeResult result = outcome.toResult(agentUri, agentNeedsUpdate);
            metrics.upgradeCompleted(result.updated(), result.created(), result.skipped(),
                    result.failures().size());
            return result;

        } catch (WebApplicationException e) {
            // A target agent that cannot be read is a 404 the operator can act on.
            // Wrapping it turned the one actionable failure of a sync into a 500.
            metrics.upgradeFailed();
            LOGGER.errorf(e, "Upgrade failed for target agent %s", targetAgentId);
            throw e;
        } catch (Exception e) {
            metrics.upgradeFailed();
            LOGGER.errorf(e, "Upgrade failed for target agent %s", targetAgentId);
            throw new RuntimeException("Upgrade failed: " + e.getMessage(), e);
        }
    }

    /**
     * Mutable tally of what an upgrade did, turned into an immutable
     * {@link UpgradeResult} at the end.
     * <p>
     * One instance belongs to exactly one {@code executeUpgrade} call and never
     * escapes it, so the bean itself stays stateless.
     */
    private static final class Outcome {
        private int updated;
        private int created;
        private int skipped;
        private final List<ResourceFailure> failures = new ArrayList<>();

        void failed(String sourceId, String resourceType, String name, Throwable cause) {
            String reason = cause.getMessage() != null
                    ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                    : cause.getClass().getSimpleName();
            failures.add(new ResourceFailure(sourceId, resourceType, name, reason));
        }

        void failed(String sourceId, String resourceType, String name, String reason) {
            failures.add(new ResourceFailure(sourceId, resourceType, name, reason));
        }

        UpgradeResult toResult(URI agentUri, boolean agentUpdated) {
            return new UpgradeResult(agentUri, agentUpdated, updated, created, skipped, List.copyOf(failures));
        }
    }

    // ==================== Snippet Processing ====================

    private void processSnippet(SnippetSourceData sourceSnippet, ResourceDiff diff, Outcome outcome) {
        try {
            if (diff.action() == DiffAction.UPDATE && diff.targetId() != null) {
                // Update existing snippet
                snippetStore.updateSnippet(diff.targetId(), diff.targetVersion(), sourceSnippet.snippet());
                outcome.updated++;
                LOGGER.infof("Updated snippet '%s' (target=%s, v%d→v%d)",
                        sourceSnippet.name(), diff.targetId(), diff.targetVersion(), diff.targetVersion() + 1);
            } else if (diff.action() == DiffAction.CREATE) {
                // Create new snippet
                snippetStore.createSnippet(sourceSnippet.snippet());
                outcome.created++;
                LOGGER.infof("Created snippet '%s'", sourceSnippet.name());
            } else {
                outcome.skipped++;
            }
        } catch (Exception e) {
            LOGGER.warnf(e, "Failed to process snippet '%s'", sourceSnippet.name());
            outcome.failed(sourceSnippet.sourceId(), "snippet", sourceSnippet.name(), e);
        }
    }

    // ==================== Workflow Extension Processing ====================

    /**
     * For each extension in a matched workflow, update the target extension with
     * the source content.
     *
     * @return map of canonical extension key → updated extension URI (with new
     *         version)
     */
    private Map<String, URI> processWorkflowExtensions(
                                                       WorkflowSourceData sourceWf,
                                                       Map<String, ResourceDiff> diffMap,
                                                       Set<String> selectedSourceIds,
                                                       Outcome outcome) {

        Map<String, URI> updates = new LinkedHashMap<>();

        for (Map.Entry<String, ExtensionSourceData> entry : sourceWf.extensions().entrySet()) {
            String extensionKey = entry.getKey();
            ExtensionSourceData sourceExt = entry.getValue();
            ResourceDiff extDiff = diffMap.get(sourceExt.sourceId());

            if (extDiff == null)
                continue;
            if (!isSelected(selectedSourceIds, sourceExt.sourceId()))
                continue;
            if (extDiff.action() == DiffAction.SKIP) {
                outcome.skipped++;
                continue;
            }

            try {
                if (extDiff.action() == DiffAction.UPDATE && extDiff.targetId() != null) {
                    URI updatedUri = updateExtension(sourceExt, extDiff.targetId(), extDiff.targetVersion(),
                            extDiff.targetContent());
                    if (updatedUri != null) {
                        updates.put(extensionKey, updatedUri);
                        outcome.updated++;
                        LOGGER.infof("Updated %s '%s' (target=%s, v%d→v%d)",
                                sourceExt.type(), sourceExt.name(),
                                extDiff.targetId(), extDiff.targetVersion(), extDiff.targetVersion() + 1);
                    } else {
                        outcome.failed(sourceExt.sourceId(), sourceExt.type(), sourceExt.name(),
                                "the store did not accept the update");
                    }
                } else if (extDiff.action() == DiffAction.CREATE) {
                    URI newUri = createExtension(sourceExt);
                    if (newUri != null) {
                        updates.put(extensionKey, newUri);
                        outcome.created++;
                        LOGGER.infof("Created %s '%s'", sourceExt.type(), sourceExt.name());
                    } else {
                        outcome.failed(sourceExt.sourceId(), sourceExt.type(), sourceExt.name(),
                                "the store did not accept the create");
                    }
                }
            } catch (Exception e) {
                LOGGER.warnf(e, "Failed to process extension %s '%s'", sourceExt.type(), sourceExt.name());
                outcome.failed(sourceExt.sourceId(), sourceExt.type(), sourceExt.name(), e);
            }
        }

        return updates;
    }

    // ==================== Extension Store Registry ====================

    /**
     * Maps an extension's file-extension label ({@code behavior},
     * {@code langchain}, …) to its configuration class and store operations. This
     * is the only place in the upgrade path that knows about concrete stores; the
     * matching side derives everything else from {@link WorkflowExtensions}.
     */
    @SuppressWarnings("unchecked")
    private <T> ExtensionStoreOps<T> resolveExtensionOps(String extensionType) {
        return (ExtensionStoreOps<T>) switch (extensionType) {
            case "regulardictionary" -> new ExtensionStoreOps<>(
                    DictionaryConfiguration.class,
                    (id, version, config) -> getStore(IRestDictionaryStore.class).updateRegularDictionary(id, version, config),
                    IRestDictionaryStore.resourceURI,
                    IRestDictionaryStore.versionQueryParam,
                    IDictionaryStore.class);
            case "behavior" -> new ExtensionStoreOps<>(
                    RuleSetConfiguration.class,
                    (id, version, config) -> getStore(IRestRuleSetStore.class).updateRuleSet(id, version, config),
                    IRestRuleSetStore.resourceURI,
                    IRestRuleSetStore.versionQueryParam,
                    IRuleSetStore.class);
            case "httpcalls" -> new ExtensionStoreOps<>(
                    ApiCallsConfiguration.class,
                    (id, version, config) -> getStore(IRestApiCallsStore.class).updateApiCalls(id, version, config),
                    IRestApiCallsStore.resourceURI,
                    IRestApiCallsStore.versionQueryParam,
                    IApiCallsStore.class);
            case "langchain" -> new ExtensionStoreOps<>(
                    LlmConfiguration.class,
                    (id, version, config) -> getStore(IRestLlmStore.class).updateLlm(id, version, config),
                    IRestLlmStore.resourceURI,
                    IRestLlmStore.versionQueryParam,
                    ILlmStore.class);
            case "property" -> new ExtensionStoreOps<>(
                    PropertySetterConfiguration.class,
                    (id, version, config) -> getStore(IRestPropertySetterStore.class).updatePropertySetter(id, version, config),
                    IRestPropertySetterStore.resourceURI,
                    IRestPropertySetterStore.versionQueryParam,
                    IPropertySetterStore.class);
            case "output" -> new ExtensionStoreOps<>(
                    OutputConfigurationSet.class,
                    (id, version, config) -> getStore(IRestOutputStore.class).updateOutputSet(id, version, config),
                    IRestOutputStore.resourceURI,
                    IRestOutputStore.versionQueryParam,
                    IOutputStore.class);
            case "mcpcalls" -> new ExtensionStoreOps<>(
                    McpCallsConfiguration.class,
                    (id, version, config) -> getStore(IRestMcpCallsStore.class).updateMcpCalls(id, version, config),
                    IRestMcpCallsStore.resourceURI,
                    IRestMcpCallsStore.versionQueryParam,
                    IMcpCallsStore.class);
            case "rag" -> new ExtensionStoreOps<>(
                    RagConfiguration.class,
                    (id, version, config) -> getStore(IRestRagStore.class).updateRag(id, version, config),
                    IRestRagStore.resourceURI,
                    IRestRagStore.versionQueryParam,
                    IRagStore.class);
            // Loud, not silent: a type registered in WorkflowExtensions but missing
            // here used to return null, which every caller turned into "the store
            // did not accept it" — a wrong diagnosis for a wiring mistake.
            default -> throw new IllegalArgumentException(
                    "No store operations are registered for extension type '" + extensionType + "'.");
        };
    }

    /** Applies the source content to an existing resource in its own store. */
    @FunctionalInterface
    private interface ExtensionUpdate<T> {
        Response apply(String targetId, Integer targetVersion, T config);
    }

    /**
     * Holds the configuration class, its store's update call, the URI pattern, and
     * the direct store class for a single extension type. The
     * {@code directStoreClass} is used by {@link #dispatchCreateDirect} to bypass
     * Response.getLocation() which fails for eddi:// scheme URIs.
     * <p>
     * The update call is a typed lambda so that dispatch happens here, in the one
     * table, instead of a second switch on the config class's <em>simple name</em>
     * — a string comparison that a class rename would have broken with no compile
     * error.
     */
    private record ExtensionStoreOps<T>(
            Class<T> configClass,
            ExtensionUpdate<T> update,
            String resourceUri,
            String versionQueryParam,
            Class<?> directStoreClass) {
    }

    // ==================== Extension Update/Create (Unified) ====================

    /**
     * Updates a target extension resource with content from the source. Dispatches
     * to the correct store via {@link #resolveExtensionOps}.
     *
     * @param targetContentJson
     *            the target's current config as JSON, used to put back values the
     *            export scrubbed — may be null when the target could not be read
     * @throws IllegalArgumentException
     *             if no store is registered for the source's extension type. That
     *             is a wiring mistake, not a store rejection, and is deliberately
     *             not caught here: swallowing it reported "the store did not accept
     *             the update" to the operator, which sends them to look at the
     *             wrong thing entirely.
     */
    private URI updateExtension(ExtensionSourceData source, String targetId, Integer targetVersion,
                                String targetContentJson) {
        ExtensionStoreOps<?> ops = resolveExtensionOps(source.type());
        try {
            String contentJson = restoreRedactedSecrets(source, source.contentJson(), targetContentJson);
            Response resp = dispatchUpdate(ops, contentJson, targetId, targetVersion);
            return resp != null && resp.getStatus() == 200
                    ? URI.create(ops.resourceUri() + targetId + ops.versionQueryParam() + (targetVersion + 1))
                    : null;
        } catch (Exception e) {
            LOGGER.warnf(e, "Failed to update %s '%s' (target=%s)", source.type(), source.name(), targetId);
            return null;
        }
    }

    /**
     * Creates a new extension resource from the source content. Uses direct store
     * create to bypass Response.getLocation() which fails for eddi:// URIs.
     *
     * @throws IllegalArgumentException
     *             if no store is registered for the source's extension type — see
     *             {@link #updateExtension} for why that one is not caught here
     */
    private URI createExtension(ExtensionSourceData source) {
        ExtensionStoreOps<?> ops = resolveExtensionOps(source.type());
        try {
            return dispatchCreateDirect(ops, source.contentJson());
        } catch (Exception e) {
            LOGGER.warnf(e, "Failed to create %s '%s'", source.type(), source.name());
            return null;
        }
    }

    /**
     * The marker {@link SecretScrubber} writes in place of a secret value.
     * <p>
     * Referenced, not re-typed: as a bare literal the two copies could drift, and
     * the drift would be silent — {@link #restoreRedactedSecrets} would simply stop
     * matching and write placeholders over a production agent's live API keys.
     */
    private static final String SCRUBBED_SECRET = SecretScrubber.REDACTED;

    /**
     * Puts the target's own value back wherever the source content carries a
     * scrubbed secret.
     * <p>
     * Everything in an export ZIP has been through the secret scrubber, which
     * replaces live credentials with {@value #SCRUBBED_SECRET}. Writing that
     * straight into the target replaced a production agent's working API keys with
     * placeholders — an upgrade from an export silently broke the agent it was
     * meant to update. A placeholder with no counterpart in the target is left
     * alone and reported, because there is nothing to preserve and the operator has
     * to supply the value.
     *
     * @return the source JSON, with scrubbed leaves replaced by the target's values
     */
    private String restoreRedactedSecrets(ExtensionSourceData source, String sourceJson, String targetJson) {
        if (sourceJson == null || !sourceJson.contains(SCRUBBED_SECRET)) {
            return sourceJson;
        }
        if (targetJson == null) {
            LOGGER.warnf("%s '%s' carries scrubbed secrets and the target's current config could not be read —"
                    + " the placeholders will be written as-is and must be replaced by hand",
                    source.type(), source.name());
            return sourceJson;
        }
        try {
            Object sourceTree = jsonSerialization.deserialize(sourceJson);
            Object targetTree = jsonSerialization.deserialize(targetJson);
            Object merged = mergeScrubbedValues(sourceTree, targetTree);
            String mergedJson = jsonSerialization.serialize(merged);
            if (mergedJson != null && mergedJson.contains(SCRUBBED_SECRET)) {
                LOGGER.warnf("%s '%s' still carries scrubbed secrets the target has no value for —"
                        + " they must be replaced by hand", source.type(), source.name());
            }
            return mergedJson != null ? mergedJson : sourceJson;
        } catch (Exception e) {
            LOGGER.warnf(e, "Could not restore scrubbed secrets for %s '%s' — writing the source content as-is",
                    source.type(), source.name());
            return sourceJson;
        }
    }

    /**
     * Walks two parsed configs in parallel and returns the source with every
     * scrubbed leaf replaced by the target's value at the same position.
     */
    private static Object mergeScrubbedValues(Object sourceNode, Object targetNode) {
        if (sourceNode instanceof String text) {
            if (text.contains(SCRUBBED_SECRET) && targetNode instanceof String targetText) {
                return targetText;
            }
            return text;
        }
        if (sourceNode instanceof Map<?, ?> sourceMap) {
            Map<?, ?> targetMap = targetNode instanceof Map<?, ?> map ? map : Map.of();
            Map<String, Object> merged = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : sourceMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                merged.put(key, mergeScrubbedValues(entry.getValue(), targetMap.get(key)));
            }
            return merged;
        }
        if (sourceNode instanceof List<?> sourceList) {
            List<?> targetList = targetNode instanceof List<?> list ? list : List.of();
            List<Object> merged = new ArrayList<>(sourceList.size());
            for (int i = 0; i < sourceList.size(); i++) {
                merged.add(mergeScrubbedValues(sourceList.get(i), i < targetList.size() ? targetList.get(i) : null));
            }
            return merged;
        }
        return sourceNode;
    }

    /**
     * Deserializes JSON and calls the store's update method through the typed
     * lambda held by {@link #resolveExtensionOps}.
     */
    private <T> Response dispatchUpdate(ExtensionStoreOps<T> ops, String json,
                                        String targetId, Integer targetVersion)
            throws Exception {
        T config = jsonSerialization.deserialize(json, ops.configClass());
        return ops.update().apply(targetId, targetVersion, config);
    }

    /**
     * Deserializes JSON and creates the resource directly via the underlying
     * I*Store, bypassing the REST layer and Response.getLocation() entirely.
     */
    @SuppressWarnings("unchecked")
    private <T> URI dispatchCreateDirect(ExtensionStoreOps<T> ops, String json) throws Exception {
        T config = jsonSerialization.deserialize(json, ops.configClass());
        IResourceStore<T> store = (IResourceStore<T>) CDI.current().select(ops.directStoreClass()).get();
        IResourceId resourceId = store.create(config);
        URI createdUri = RestUtilities.createURI(ops.resourceUri(), resourceId.getId(), ops.versionQueryParam(), resourceId.getVersion());

        // Create the DocumentDescriptor that the DocumentDescriptorFilter would
        // normally create on a 201 response — including the ownership stamp, or a
        // resource created by an upgrade would be unowned and unlistable.
        documentDescriptorStore.createDescriptor(resourceId.getId(), resourceId.getVersion(),
                resourceAccessGuard.stampNewDescriptor(createDocumentDescriptor(createdUri)));

        return createdUri;
    }

    // ==================== Workflow Updates ====================

    /**
     * Creates a new workflow using direct store access, bypassing
     * Response.getLocation() which fails for eddi:// scheme URIs.
     */
    private URI createNewWorkflow(WorkflowSourceData sourceWf, Outcome outcome) {
        try {
            IWorkflowStore store = CDI.current().select(IWorkflowStore.class).get();
            IResourceId resourceId = store.create(sourceWf.config());
            URI createdUri = RestUtilities.createURI(IRestWorkflowStore.resourceURI, resourceId.getId(),
                    IRestWorkflowStore.versionQueryParam, resourceId.getVersion());

            // Create the DocumentDescriptor that the DocumentDescriptorFilter would
            // normally create on a 201 response — ownership stamp included.
            documentDescriptorStore.createDescriptor(resourceId.getId(), resourceId.getVersion(),
                    resourceAccessGuard.stampNewDescriptor(createDocumentDescriptor(createdUri)));

            return createdUri;
        } catch (Exception e) {
            LOGGER.warnf(e, "Failed to create workflow '%s'", sourceWf.name());
            outcome.failed(sourceWf.sourceId(), "workflow", sourceWf.name(), e);
            return null;
        }
    }

    /**
     * Updates the extension URIs within a target workflow's config. When an
     * extension was updated (version incremented), the workflow config needs to
     * point to the new version.
     * <p>
     * The new URI is written into the step's {@code config} map — the one the
     * engine reads. Writing it into {@code extensions} left the deployed pipeline
     * loading the OLD extension version while the agent version was bumped, and
     * left a stray {@code extensions.uri} that reference scans do not count, so the
     * resource it named looked orphaned.
     */
    private URI updateWorkflowExtensionUris(String workflowId, Integer workflowVersion,
                                            Map<String, URI> extensionUpdates, Outcome outcome) {
        try {
            WorkflowConfiguration wfConfig = workflowStore.readWorkflow(workflowId, workflowVersion);

            boolean changed = false;
            for (WorkflowExtensions.ExtensionRef ref : WorkflowExtensions.scan(wfConfig)) {
                URI newExtUri = extensionUpdates.get(ref.key());
                if (newExtUri != null) {
                    ref.repointTo(newExtUri);
                    changed = true;
                }
            }

            if (changed) {
                Response resp = workflowStore.updateWorkflow(workflowId, workflowVersion, wfConfig);
                if (resp != null && resp.getStatus() == 200) {
                    return URI.create(IRestWorkflowStore.resourceURI + workflowId
                            + IRestWorkflowStore.versionQueryParam + (workflowVersion + 1));
                }
                outcome.failed(workflowId, "workflow", null,
                        "the workflow store did not accept the updated extension URIs");
            }

            return null;
        } catch (Exception e) {
            LOGGER.warnf(e, "Failed to update workflow URIs %s", workflowId);
            outcome.failed(workflowId, "workflow", null, e);
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
                LOGGER.infof("Agent '%s' upgraded successfully (v%d→v%d)", agentId, currentVersion, currentVersion + 1);
                return updatedUri;
            }

            return null;
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to update agent config %s", agentId);
            throw new RuntimeException("Agent config update failed: " + e.getMessage(), e);
        }
    }

    /**
     * The agent's current URI, for an upgrade that had nothing to write.
     * <p>
     * This one does not fall back to version 1. {@link #readLatestVersion} swallows
     * every descriptor failure and answers 1, which on this path handed the caller
     * {@code ?version=1} for an agent that may well be at v14 — a wrong version
     * number reported as the outcome of a successful no-op sync. When the version
     * cannot be established the URI is returned without a {@code ?version=}
     * parameter instead, which is this codebase's "unspecified, i.e. latest" form
     * and states no number the deployment might not agree with.
     */
    private URI currentAgentUri(String agentId) {
        Integer currentVersion = resolveLatestVersion(agentId);
        if (currentVersion == null) {
            LOGGER.warnf("Could not establish the current version of agent %s — reporting its URI without one"
                    + " rather than guessing", agentId);
            return URI.create(IRestAgentStore.resourceURI + agentId);
        }
        return URI.create(IRestAgentStore.resourceURI + agentId + "?version=" + currentVersion);
    }

    /**
     * Reads the latest version of a resource via its descriptor.
     */
    private int readLatestVersion(String resourceId) {
        Integer version = resolveLatestVersion(resourceId);
        return version != null ? version : 1;
    }

    /**
     * The latest version of a resource per its descriptor, or {@code null} when
     * that cannot be established — an unreadable descriptor, one with no resource
     * URI, or one whose URI carries no resource id.
     */
    private Integer resolveLatestVersion(String resourceId) {
        try {
            DocumentDescriptor desc = documentDescriptorStore.readDescriptor(resourceId, null);
            if (desc != null && desc.getResource() != null) {
                IResourceId resId = RestUtilities.extractResourceId(desc.getResource());
                if (resId != null)
                    return resId.getVersion();
            }
        } catch (Exception e) {
            LOGGER.debugf(e, "Could not find latest version for %s", resourceId);
        }
        return null;
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

    private <T> T getStore(Class<T> clazz) {
        return CDI.current().select(clazz).get();
    }
}
