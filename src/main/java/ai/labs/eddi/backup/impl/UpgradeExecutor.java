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
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.utils.LogSanitizer;
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
                } else {
                    // Matched workflow — process its extensions whatever the
                    // workflow-level action says.
                    //
                    // The action is decided from the workflow JSON alone, and on the
                    // most natural sync path — export an agent, edit one extension in
                    // the ZIP, upgrade the same agent — that JSON is byte-identical on
                    // both sides, so the workflow came back SKIP. Reading the extension
                    // diffs only inside the UPDATE branch therefore threw away every
                    // extension change the preview had just shown the operator, and the
                    // response still said 200 OK.
                    Map<String, URI> extensionUpdates = processWorkflowExtensions(
                            sourceWf, diffMap, selectedSourceIds, outcome);

                    // The workflow document itself changed — reordered steps, a
                    // changed step config, a condition — and the preview said so.
                    // Counting that as "skipped" and writing nothing told the
                    // operator the sync was a no-op while their edit was dropped.
                    boolean adoptSourceConfig = wfDiff.action() == DiffAction.UPDATE
                            && isSelected(selectedSourceIds, sourceWf.sourceId());

                    int failuresBefore = outcome.failures.size();
                    URI updatedUri = extensionUpdates.isEmpty() && !adoptSourceConfig
                            ? null
                            : updateMatchedWorkflow(sourceWf, wfDiff, extensionUpdates,
                                    adoptSourceConfig, outcome);
                    if (updatedUri != null) {
                        updatedWorkflowUris.put(wfDiff.targetId(), updatedUri);
                        outcome.updated++;
                    } else if (extensionUpdates.isEmpty() && outcome.failures.size() == failuresBefore) {
                        outcome.skipped++;
                    }
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
                        LogSanitizer.sanitize(targetAgentId), LogSanitizer.sanitize(String.valueOf(agentUri)));
            }

            UpgradeResult result = outcome.toResult(agentUri, agentNeedsUpdate);
            metrics.upgradeCompleted(result.updated(), result.created(), result.skipped(),
                    result.failures().size());
            return result;

        } catch (WebApplicationException e) {
            // A target agent that cannot be read is a 404 the operator can act on.
            // Wrapping it turned the one actionable failure of a sync into a 500.
            metrics.upgradeFailed();
            LOGGER.errorf(e, "Upgrade failed for target agent %s", LogSanitizer.sanitize(targetAgentId));
            throw e;
        } catch (Exception e) {
            metrics.upgradeFailed();
            LOGGER.errorf(e, "Upgrade failed for target agent %s", LogSanitizer.sanitize(targetAgentId));
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
                        LogSanitizer.sanitize(sourceSnippet.name()), LogSanitizer.sanitize(diff.targetId()), diff.targetVersion(),
                        diff.targetVersion() + 1);
            } else if (diff.action() == DiffAction.CREATE) {
                // Create new snippet
                snippetStore.createSnippet(sourceSnippet.snippet());
                outcome.created++;
                LOGGER.infof("Created snippet '%s'", LogSanitizer.sanitize(sourceSnippet.name()));
            } else {
                outcome.skipped++;
            }
        } catch (Exception e) {
            LOGGER.warnf(e, "Failed to process snippet '%s'", LogSanitizer.sanitize(sourceSnippet.name()));
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
                                LogSanitizer.sanitize(sourceExt.type()), LogSanitizer.sanitize(sourceExt.name()),
                                LogSanitizer.sanitize(extDiff.targetId()), extDiff.targetVersion(), extDiff.targetVersion() + 1);
                    } else {
                        outcome.failed(sourceExt.sourceId(), sourceExt.type(), sourceExt.name(),
                                "the store did not accept the update");
                    }
                } else if (extDiff.action() == DiffAction.CREATE) {
                    // Deliberately NOT created. The only thing that would consume the
                    // new URI is updateWorkflowExtensionUris, which can repoint a
                    // reference the target workflow already has — and CREATE means, by
                    // definition, that it has none. Creating the resource anyway left
                    // it in the store with nothing pointing at it, counted it as
                    // created, answered 201, changed the agent's behaviour not at all,
                    // and previewed the same CREATE again on the next sync, so every
                    // run added another unreferenced copy.
                    //
                    // Cloning the source's step into the target workflow instead was
                    // considered and rejected: the preview has no row for "a step will
                    // be added to your existing pipeline", so it would reshape a live
                    // agent's pipeline off the back of a resource row the operator
                    // approved as a config change.
                    outcome.failed(sourceExt.sourceId(), sourceExt.type(), sourceExt.name(),
                            "the target workflow has no step referencing this " + sourceExt.type()
                                    + " — add the step to the target workflow, or import the source"
                                    + " workflow as a new one, then sync again");
                    LOGGER.warnf("Skipped %s '%s': the target workflow has no step to reference it",
                            LogSanitizer.sanitize(sourceExt.type()), LogSanitizer.sanitize(sourceExt.name()));
                }
            } catch (Exception e) {
                LOGGER.warnf(e, "Failed to process extension %s '%s'", LogSanitizer.sanitize(sourceExt.type()),
                        LogSanitizer.sanitize(sourceExt.name()));
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
                    IRestDictionaryStore.versionQueryParam);
            case "behavior" -> new ExtensionStoreOps<>(
                    RuleSetConfiguration.class,
                    (id, version, config) -> getStore(IRestRuleSetStore.class).updateRuleSet(id, version, config),
                    IRestRuleSetStore.resourceURI,
                    IRestRuleSetStore.versionQueryParam);
            case "httpcalls" -> new ExtensionStoreOps<>(
                    ApiCallsConfiguration.class,
                    (id, version, config) -> getStore(IRestApiCallsStore.class).updateApiCalls(id, version, config),
                    IRestApiCallsStore.resourceURI,
                    IRestApiCallsStore.versionQueryParam);
            case "langchain" -> new ExtensionStoreOps<>(
                    LlmConfiguration.class,
                    (id, version, config) -> getStore(IRestLlmStore.class).updateLlm(id, version, config),
                    IRestLlmStore.resourceURI,
                    IRestLlmStore.versionQueryParam);
            case "property" -> new ExtensionStoreOps<>(
                    PropertySetterConfiguration.class,
                    (id, version, config) -> getStore(IRestPropertySetterStore.class).updatePropertySetter(id, version, config),
                    IRestPropertySetterStore.resourceURI,
                    IRestPropertySetterStore.versionQueryParam);
            case "output" -> new ExtensionStoreOps<>(
                    OutputConfigurationSet.class,
                    (id, version, config) -> getStore(IRestOutputStore.class).updateOutputSet(id, version, config),
                    IRestOutputStore.resourceURI,
                    IRestOutputStore.versionQueryParam);
            case "mcpcalls" -> new ExtensionStoreOps<>(
                    McpCallsConfiguration.class,
                    (id, version, config) -> getStore(IRestMcpCallsStore.class).updateMcpCalls(id, version, config),
                    IRestMcpCallsStore.resourceURI,
                    IRestMcpCallsStore.versionQueryParam);
            case "rag" -> new ExtensionStoreOps<>(
                    RagConfiguration.class,
                    (id, version, config) -> getStore(IRestRagStore.class).updateRag(id, version, config),
                    IRestRagStore.resourceURI,
                    IRestRagStore.versionQueryParam);
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
     * Holds the configuration class, its store's update call and the URI pattern
     * for a single extension type.
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
            String versionQueryParam) {
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
            LOGGER.warnf(e, "Failed to update %s '%s' (target=%s)", LogSanitizer.sanitize(source.type()), LogSanitizer.sanitize(source.name()),
                    LogSanitizer.sanitize(targetId));
            return null;
        }
    }

    /**
     * Puts the target's own value back wherever the source content carries a
     * scrubbed secret.
     * <p>
     * Everything in an export ZIP has been through the secret scrubber, which
     * replaces live credentials with a placeholder. Writing that straight into the
     * target replaced a production agent's working API keys with placeholders — an
     * upgrade from an export silently broke the agent it was meant to update. A
     * placeholder with no counterpart in the target is left alone and reported,
     * because there is nothing to preserve and the operator has to supply the
     * value.
     * <p>
     * The merge itself lives in {@link ScrubbedSecrets} so that
     * {@link StructuralMatcher} decides its {@link DiffAction} on exactly the
     * content this method is about to write.
     *
     * @return the source JSON, with scrubbed leaves replaced by the target's values
     */
    private String restoreRedactedSecrets(ExtensionSourceData source, String sourceJson, String targetJson) {
        if (!ScrubbedSecrets.carriesPlaceholder(sourceJson)) {
            return sourceJson;
        }
        if (targetJson == null) {
            LOGGER.warnf("%s '%s' carries scrubbed secrets and the target's current config could not be read —"
                    + " the placeholders will be written as-is and must be replaced by hand",
                    LogSanitizer.sanitize(source.type()), LogSanitizer.sanitize(source.name()));
            return sourceJson;
        }
        try {
            String mergedJson = ScrubbedSecrets.restore(sourceJson, targetJson, jsonSerialization);
            if (ScrubbedSecrets.carriesPlaceholder(mergedJson)) {
                LOGGER.warnf("%s '%s' still carries scrubbed secrets the target has no value for —"
                        + " they must be replaced by hand",
                        LogSanitizer.sanitize(source.type()), LogSanitizer.sanitize(source.name()));
            }
            return mergedJson != null ? mergedJson : sourceJson;
        } catch (Exception e) {
            LOGGER.warnf(e, "Could not restore scrubbed secrets for %s '%s' — writing the source content as-is",
                    LogSanitizer.sanitize(source.type()), LogSanitizer.sanitize(source.name()));
            return sourceJson;
        }
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
            LOGGER.warnf(e, "Failed to create workflow '%s'", LogSanitizer.sanitize(sourceWf.name()));
            outcome.failed(sourceWf.sourceId(), "workflow", sourceWf.name(), e);
            return null;
        }
    }

    /**
     * Writes the target workflow: its own document when the source workflow changed
     * and can be adopted, plus the extension URIs of everything this run
     * re-versioned.
     * <p>
     * The new URI is written into the step's {@code config} map — the one the
     * engine reads. Writing it into {@code extensions} left the deployed pipeline
     * loading the OLD extension version while the agent version was bumped, and
     * left a stray {@code extensions.uri} that reference scans do not count, so the
     * resource it named looked orphaned.
     * <p>
     * Any key this method cannot place is reported as a failure rather than
     * dropped. A written resource whose URI nothing consumes is an orphan the
     * operator is never told about, and the run would still answer success.
     *
     * @param adoptSourceConfig
     *            whether the preview said the workflow document itself changed, so
     *            the source's steps replace the target's. Honoured only when the
     *            two workflows wire up the same extensions — see
     *            {@link #adoptableSourceConfig}
     * @return the workflow's new version URI, or null when nothing was written
     */
    private URI updateMatchedWorkflow(WorkflowSourceData sourceWf, ResourceDiff wfDiff,
                                      Map<String, URI> extensionUpdates,
                                      boolean adoptSourceConfig, Outcome outcome) {
        String workflowId = wfDiff.targetId();
        Integer workflowVersion = wfDiff.targetVersion();
        try {
            WorkflowConfiguration targetConfig = workflowStore.readWorkflow(workflowId, workflowVersion);

            // Where the target currently points, so an adopted source config can be
            // rewritten onto the target's own resources instead of the source
            // instance's ids, which do not exist here.
            Map<String, URI> targetRefs = new LinkedHashMap<>();
            for (WorkflowExtensions.ExtensionRef ref : WorkflowExtensions.scan(targetConfig)) {
                targetRefs.put(ref.key(), ref.extensionUri());
            }

            String refusedAdoption = null;
            WorkflowConfiguration configToWrite = targetConfig;
            if (adoptSourceConfig && targetConfig == null) {
                LOGGER.warnf("Workflow %s changed in the source but its current version could not be read —"
                        + " its steps are left as they are", LogSanitizer.sanitize(workflowId));
            } else if (adoptSourceConfig && hasSteps(sourceWf.config())) {
                refusedAdoption = adoptableSourceConfig(sourceWf.config(), targetRefs);
                if (refusedAdoption == null) {
                    configToWrite = sourceWf.config();
                }
            }

            boolean changed = configToWrite != null && configToWrite != targetConfig;
            Set<String> unconsumed = new LinkedHashSet<>(extensionUpdates.keySet());
            for (WorkflowExtensions.ExtensionRef ref : WorkflowExtensions.scan(configToWrite)) {
                URI newExtUri = extensionUpdates.get(ref.key());
                if (newExtUri != null) {
                    unconsumed.remove(ref.key());
                } else if (configToWrite != targetConfig) {
                    newExtUri = targetRefs.get(ref.key());
                }
                if (newExtUri != null && !newExtUri.equals(ref.extensionUri())) {
                    ref.repointTo(newExtUri);
                    changed = true;
                }
            }

            for (String key : unconsumed) {
                outcome.failed(workflowId, "workflow", null,
                        "the target workflow has no reference at '" + key + "' for "
                                + extensionUpdates.get(key) + ", so the updated resource is not deployed");
            }
            if (refusedAdoption != null) {
                outcome.failed(workflowId, "workflow", sourceWf.name(), refusedAdoption);
            }

            // Adopting a config that repoints to exactly what the target already has
            // is the cross-instance no-op: same steps, different resource ids. Writing
            // it would burn a workflow and an agent version to change nothing.
            if (changed && configToWrite != targetConfig && sameSteps(configToWrite, targetConfig)) {
                changed = false;
            }

            if (changed) {
                Response resp = workflowStore.updateWorkflow(workflowId, workflowVersion, configToWrite);
                if (resp != null && resp.getStatus() == 200) {
                    return URI.create(IRestWorkflowStore.resourceURI + workflowId
                            + IRestWorkflowStore.versionQueryParam + (workflowVersion + 1));
                }
                outcome.failed(workflowId, "workflow", null,
                        "the workflow store did not accept the updated workflow");
            }

            return null;
        } catch (Exception e) {
            LOGGER.warnf(e, "Failed to update workflow %s", LogSanitizer.sanitize(workflowId));
            outcome.failed(workflowId, "workflow", null, e);
            return null;
        }
    }

    /**
     * Whether the source workflow's steps may replace the target's, or the reason
     * they may not.
     * <p>
     * Only when both sides wire up the same extensions. A source step referencing
     * something the target workflow does not have would be written pointing at a
     * resource id from the other instance — a pipeline step loading a config that
     * is not in this database. That is the same refusal
     * {@link #processWorkflowExtensions} makes for a CREATE extension, applied to
     * the step that would reference it.
     *
     * @return null when the source config can be adopted, otherwise the reason for
     *         the operator
     */
    private String adoptableSourceConfig(WorkflowConfiguration sourceConfig, Map<String, URI> targetRefs) {
        for (WorkflowExtensions.ExtensionRef ref : WorkflowExtensions.scan(sourceConfig)) {
            if (!targetRefs.containsKey(ref.key())) {
                return "the source workflow's steps were not applied: it references a " + ref.fileExtension()
                        + " at '" + ref.key() + "' that the target workflow does not have"
                        + " — add the step to the target workflow, or import the source workflow as a new one";
            }
        }
        return null;
    }

    /**
     * Whether a source workflow carries a pipeline to apply at all.
     * <p>
     * A source with no steps is left alone rather than adopted. A workflow with
     * zero steps deploys an agent that runs no lifecycle task and answers nothing
     * (see {@link WorkflowConfiguration#setWorkflowSteps}), so emptying a live
     * pipeline is never what a sync is for — and it is what an unparsed or
     * partially read source workflow looks like.
     */
    private static boolean hasSteps(WorkflowConfiguration config) {
        return config != null && config.getWorkflowSteps() != null && !config.getWorkflowSteps().isEmpty();
    }

    /**
     * Whether two workflow documents describe the same pipeline.
     * {@link WorkflowConfiguration} has no {@code equals}, and the comparison has
     * to hold without a serializer so that it cannot itself fail.
     */
    private static boolean sameSteps(WorkflowConfiguration left, WorkflowConfiguration right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        List<WorkflowConfiguration.WorkflowStep> leftSteps = left.getWorkflowSteps();
        List<WorkflowConfiguration.WorkflowStep> rightSteps = right.getWorkflowSteps();
        if (leftSteps == null || rightSteps == null) {
            return leftSteps == rightSteps;
        }
        if (leftSteps.size() != rightSteps.size()) {
            return false;
        }
        for (int i = 0; i < leftSteps.size(); i++) {
            WorkflowConfiguration.WorkflowStep leftStep = leftSteps.get(i);
            WorkflowConfiguration.WorkflowStep rightStep = rightSteps.get(i);
            if (leftStep == null || rightStep == null) {
                if (leftStep != rightStep) {
                    return false;
                }
                continue;
            }
            if (!Objects.equals(leftStep.getType(), rightStep.getType())
                    || !Objects.equals(leftStep.getConfig(), rightStep.getConfig())
                    || !Objects.equals(leftStep.getExtensions(), rightStep.getExtensions())) {
                return false;
            }
        }
        return true;
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
                LOGGER.infof("Agent '%s' upgraded successfully (v%d→v%d)", LogSanitizer.sanitize(agentId), currentVersion,
                        currentVersion + 1);
                return updatedUri;
            }

            return null;
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to update agent config %s", LogSanitizer.sanitize(agentId));
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
                    + " rather than guessing", LogSanitizer.sanitize(agentId));
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
            LOGGER.debugf(e, "Could not find latest version for %s", LogSanitizer.sanitize(resourceId));
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
