/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.engine.runtime.rest.interceptors.DocumentDescriptorFilter;
import ai.labs.eddi.engine.runtime.service.WorkflowStoreService;
import ai.labs.eddi.engine.security.spaces.DescriptorAccess;
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
import ai.labs.eddi.configs.parser.IParserStore;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
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

import static ai.labs.eddi.backup.impl.AbstractBackupService.PARSER_EXT;
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
                processSnippet(snippet, diff, selectedSourceIds, outcome);
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
                        URI newUri = createNewWorkflow(sourceWf, selectedSourceIds, outcome);
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
                    // The workflow document itself changed — reordered steps, a
                    // changed step config, a condition — and the preview said so.
                    // Counting that as "skipped" and writing nothing told the
                    // operator the sync was a no-op while their edit was dropped.
                    //
                    // A CONFLICT — steps changed on this instance since the last sync —
                    // is adopted only when named; otherwise the extensions below are
                    // still written and repointed, and only the step structure stays.
                    DiffAction workflowAction = isSelected(selectedSourceIds, sourceWf.sourceId())
                            ? resolveConflict(wfDiff, selectedSourceIds, "workflow", sourceWf.name(), outcome)
                            : wfDiff.action();
                    boolean adoptSourceConfig = workflowAction == DiffAction.UPDATE
                            && isSelected(selectedSourceIds, sourceWf.sourceId());

                    // Decided before anything is written: a resource for a step the
                    // source adds may only be created when the adoption that places it
                    // is certain to happen. Deciding it afterwards left the resource
                    // behind, unreferenced, whenever the adoption was then refused.
                    String adoptionBlocker = adoptSourceConfig
                            ? adoptionBlocker(sourceWf, wfDiff, diffMap, selectedSourceIds)
                            : workflowAction == null
                                    ? "the workflow was changed on this instance and is left alone"
                                    : wfDiff.action() == DiffAction.SKIP
                                            ? "the source workflow itself did not change"
                                            : "the workflow was left out of the selection";

                    Set<String> createdForAdoption = new HashSet<>();
                    Map<String, URI> extensionUpdates = processWorkflowExtensions(
                            sourceWf, wfDiff, diffMap, selectedSourceIds, adoptionBlocker, createdForAdoption,
                            outcome);

                    int failuresBefore = outcome.failures.size();
                    URI updatedUri = extensionUpdates.isEmpty() && !adoptSourceConfig
                            ? null
                            : updateMatchedWorkflow(sourceWf, wfDiff, extensionUpdates,
                                    adoptSourceConfig, createdForAdoption, outcome);
                    if (updatedUri != null) {
                        updatedWorkflowUris.put(wfDiff.targetId(), updatedUri);
                        // Counted only when nothing failed on the way: a workflow whose
                        // descriptor still names the old version was written but cannot be
                        // deployed, and reporting it as updated overstates what landed.
                        if (outcome.failures.size() == failuresBefore) {
                            outcome.updated++;
                        }
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
            // Workflows the source no longer has. They come off the agent; the documents
            // stay in the store, because earlier agent versions still name them.
            Set<String> removedWorkflowIds = new LinkedHashSet<>();
            for (ResourceDiff diff : preview.resources()) {
                if ("workflow".equals(diff.resourceType()) && diff.action() == DiffAction.REMOVE
                        && diff.targetId() != null && isSelected(selectedSourceIds, diff.sourceId())) {
                    removedWorkflowIds.add(diff.targetId());
                }
            }

            // The agent's own settings — HITL, capabilities, memory policy, channels.
            // Only workflows were ever written, so a changed setting showed as an
            // agent UPDATE in the preview and then never arrived. The preview's
            // source content is already rewritten onto the target (its workflows, its
            // identity, its secrets), so it is exactly what is written.
            ResourceDiff agentDiff = preview.resources().stream()
                    .filter(diff -> "agent".equals(diff.resourceType()))
                    .findFirst().orElse(null);
            // A CONFLICT — settings changed here since the last sync — is taken only
            // when named; the workflow changes above are written either way.
            DiffAction agentAction = agentDiff != null && isSelected(selectedSourceIds, agentDiff.sourceId())
                    ? resolveConflict(agentDiff, selectedSourceIds, "agent", agentDiff.name(), outcome)
                    : null;
            String agentSettings = agentAction == DiffAction.UPDATE ? agentDiff.sourceContent() : null;

            boolean agentMayChange = !updatedWorkflowUris.isEmpty()
                    || !newWorkflowUris.isEmpty()
                    || !removedWorkflowIds.isEmpty()
                    || agentSettings != null
                    || (workflowOrder != null && !workflowOrder.isEmpty());

            URI writtenAgentUri = agentMayChange
                    ? updateAgentConfig(targetAgentId, updatedWorkflowUris, newWorkflowUris, removedWorkflowIds,
                            agentSettings, workflowOrder, outcome)
                    : null;
            boolean agentUpdated = writtenAgentUri != null;
            URI agentUri = agentUpdated ? writtenAgentUri : currentAgentUri(targetAgentId);

            if (!agentUpdated) {
                LOGGER.infof("Agent '%s' upgrade wrote no agent changes — agent version left at %s",
                        LogSanitizer.sanitize(targetAgentId), LogSanitizer.sanitize(String.valueOf(agentUri)));
            }

            UpgradeResult result = outcome.toResult(agentUri, agentUpdated);
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

    private void processSnippet(SnippetSourceData sourceSnippet, ResourceDiff diff, Set<String> selectedSourceIds,
                                Outcome outcome) {
        DiffAction action = resolveConflict(diff, selectedSourceIds, "snippet", sourceSnippet.name(), outcome);
        if (action == null) {
            return;
        }
        try {
            if (action == DiffAction.UPDATE && diff.targetId() != null) {
                // Update existing snippet. The store answers a non-200 without
                // throwing, and advancing the descriptor past a write that did not
                // happen points every reader at a version that does not exist.
                Response updated = snippetStore.updateSnippet(diff.targetId(), diff.targetVersion(), sourceSnippet.snippet());
                if (updated == null || updated.getStatus() != 200) {
                    outcome.failed(sourceSnippet.sourceId(), "snippet", sourceSnippet.name(),
                            "the store did not accept the update");
                    return;
                }
                if (bumpDescriptorOrFail(diff.targetId(), diff.targetVersion(), "snippet",
                        sourceSnippet.sourceId(), sourceSnippet.name(), outcome)) {
                    outcome.updated++;
                }
                LOGGER.infof("Updated snippet '%s' (target=%s, v%d→v%d)",
                        LogSanitizer.sanitize(sourceSnippet.name()), LogSanitizer.sanitize(diff.targetId()), diff.targetVersion(),
                        diff.targetVersion() + 1);
            } else if (action == DiffAction.CREATE) {
                // Create new snippet
                Response created = snippetStore.createSnippet(sourceSnippet.snippet());
                createDescriptorFor(created, "snippet", sourceSnippet.sourceId(), sourceSnippet.name(), outcome);
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

    /**
     * What to do with a resource the preview called CONFLICT — changed on this
     * instance since the last sync wrote it, and changed on the source too.
     * <p>
     * Written only when the caller named it in {@code selectedResources}: that is
     * the operator deciding the source wins. A sync of everything leaves it alone
     * and says so, because silently overwriting a production hotfix with the
     * staging copy is the one outcome a promotion must never produce unasked.
     *
     * @return the action to carry out — the diff's own, UPDATE for a conflict the
     *         caller chose to overwrite — or null when it is left alone (the reason
     *         is recorded)
     */
    private static DiffAction resolveConflict(ResourceDiff diff, Set<String> selectedSourceIds, String resourceType,
                                              String name, Outcome outcome) {
        if (diff.action() != DiffAction.CONFLICT) {
            return diff.action();
        }
        if (selectedSourceIds != null && selectedSourceIds.contains(diff.sourceId())) {
            return DiffAction.UPDATE;
        }
        outcome.failed(diff.sourceId(), resourceType, name,
                "it was changed on this instance since the last sync wrote it (now v" + diff.targetVersion()
                        + "), and the source changed it too — it was left alone. To overwrite the local change,"
                        + " select it explicitly (selectedResources); or carry that change back to the source first");
        return null;
    }

    /**
     * Writes the {@link DocumentDescriptor} for a resource this run created.
     * <p>
     * Nothing else will. A create through the HTTP API gets its descriptor from
     * {@code DocumentDescriptorFilter}; an in-process create gets nothing, and for
     * a snippet that is not cosmetic — {@code PromptSnippetService} enumerates
     * snippets <em>by descriptor</em>, so one without a descriptor never resolves
     * in a template, and {@link StructuralMatcher#buildExistingSnippetNameMap}
     * lists them the same way, so the next preview offers the same snippet as
     * CREATE again and every sync writes another copy.
     *
     * @param createResponse
     *            the store's answer to the create
     */
    private void createDescriptorFor(Response createResponse, String resourceType, String sourceId,
                                     String name, Outcome outcome) {
        String createdUri = createResponse == null ? null : createResponse.getHeaderString("X-Resource-URI");
        if (createdUri == null || createdUri.isBlank()) {
            outcome.failed(sourceId, resourceType, name,
                    "it was created but the store named no resource URI, so no descriptor could be written"
                            + " and nothing will find it");
            return;
        }
        try {
            URI uri = URI.create(createdUri);
            IResourceId resourceId = RestUtilities.extractResourceId(uri);
            if (resourceId == null || resourceId.getId() == null) {
                throw new IllegalStateException("created URI carries no resource id: " + createdUri);
            }
            DocumentDescriptor descriptor = createDocumentDescriptor(uri);
            // Named, so the Manager's list does not show a blank row and the matcher
            // can pair it by name on the next sync without reading the document.
            descriptor.setName(name);
            descriptor.setSyncedVersion(resourceId.getVersion());
            documentDescriptorStore.createDescriptor(resourceId.getId(), resourceId.getVersion(),
                    resourceAccessGuard.stampNewDescriptor(descriptor));
        } catch (Exception e) {
            LOGGER.warnf(e, "Created %s '%s' but could not write its descriptor",
                    LogSanitizer.sanitize(resourceType), LogSanitizer.sanitize(name));
            outcome.failed(sourceId, resourceType, name,
                    "it was created but its descriptor could not be written, so nothing will find it: "
                            + e.getMessage());
        }
    }

    // ==================== Workflow Extension Processing ====================

    /**
     * For each extension in a matched workflow, update the target extension with
     * the source content.
     *
     * @param adoptionBlocker
     *            null when the source workflow's steps will replace the target's —
     *            a resource for a step the target does not have is then created and
     *            wired in by the adoption; otherwise why they will not, for the
     *            operator
     * @param createdForAdoption
     *            receives the key of every resource created for the adoption to
     *            place — the only references the adoption may add
     * @return map of canonical extension key → updated extension URI (with new
     *         version), for the workflow's own references only — a resource a
     *         parser document names is repointed in that document, not in the
     *         workflow
     */
    private Map<String, URI> processWorkflowExtensions(
                                                       WorkflowSourceData sourceWf,
                                                       ResourceDiff wfDiff,
                                                       Map<String, ResourceDiff> diffMap,
                                                       Set<String> selectedSourceIds,
                                                       String adoptionBlocker,
                                                       Set<String> createdForAdoption,
                                                       Outcome outcome) {

        Map<String, URI> updates = new LinkedHashMap<>();
        // Target resource id -> the URI this run wrote it at, for the parser
        // documents that name those resources and are written after them.
        Map<String, URI> writtenThisRun = new HashMap<>();
        // Source resource id -> where that resource lives on the target now: what a
        // parser document's references are repointed at.
        Map<String, URI> onTargetBySourceId = new HashMap<>();
        // The target workflow's own references, read once and only if a resource
        // turns out to be missing from the target.
        Map<String, WorkflowExtensions.ExtensionRef> targetRefs = null;

        for (Map.Entry<String, ExtensionSourceData> entry : parsersLast(sourceWf.extensions())) {
            String extensionKey = entry.getKey();
            ExtensionSourceData sourceExt = entry.getValue();
            ResourceDiff extDiff = diffMap.get(sourceExt.sourceId());
            boolean inDocument = WorkflowExtensions.isInDocument(extensionKey);

            if (extDiff == null)
                continue;
            if (extDiff.targetId() != null && extDiff.targetVersion() != null) {
                URI onTarget = targetUriOf(sourceExt.type(), extDiff.targetId(), extDiff.targetVersion());
                if (onTarget != null) {
                    onTargetBySourceId.put(sourceExt.sourceId(), onTarget);
                }
            }
            if (!isSelected(selectedSourceIds, sourceExt.sourceId()))
                continue;

            DiffAction action = resolveConflict(extDiff, selectedSourceIds, sourceExt.type(), sourceExt.name(), outcome);
            if (action == null) {
                continue;
            }
            if (inDocument && (action == DiffAction.CREATE || action == DiffAction.UPDATE)) {
                // Only its parser document names this dictionary, and that document is
                // written after it. When the document will not be written this run, a
                // created dictionary is an orphan the next sync creates again, and an
                // updated one is a version nothing loads — so neither is written.
                if (targetRefs == null) {
                    targetRefs = targetReferences(wfDiff);
                }
                String ownerBlocker = documentOwnerBlocker(extensionKey, sourceWf, diffMap, selectedSourceIds,
                        adoptionBlocker, targetRefs);
                if (ownerBlocker != null) {
                    outcome.failed(sourceExt.sourceId(), sourceExt.type(), sourceExt.name(),
                            "only its parser document names it, and that document is not written this run: " + ownerBlocker);
                    continue;
                }
            }
            if (PARSER_EXT.equals(sourceExt.type())) {
                // The source's parser names the source's dictionaries. Written as it
                // is, the target's parser would name ids that exist only on the source
                // — so each one is swapped for its counterpart on the target, at the
                // version this run wrote where it wrote one.
                sourceExt = new ExtensionSourceData(sourceExt.sourceId(), sourceExt.name(), sourceExt.type(),
                        sourceExt.stepType(),
                        NestedReferences.repointBySourceId(sourceExt.contentJson(), onTargetBySourceId));
                // The preview already says UPDATE when a dictionary the parser names is
                // written; this holds the same for a write the preview could not foresee
                // (a conflict retry that moved a version), so the parser never keeps
                // naming the version just superseded.
                if (action == DiffAction.SKIP
                        && NestedReferences.namesAny(extDiff.targetContent(), writtenThisRun.keySet())) {
                    action = DiffAction.UPDATE;
                }
            }
            if (action == DiffAction.SKIP) {
                outcome.skipped++;
                continue;
            }

            try {
                if (action == DiffAction.UPDATE && extDiff.targetId() != null) {
                    WrittenExtension written = updateExtension(sourceExt, extDiff.targetId(), extDiff.targetVersion(),
                            extDiff.targetContent());
                    if (written != null) {
                        // Only a resource whose descriptor now names the new version counts
                        // as updated: one the deployment cannot resolve was written, but it
                        // was not delivered, and counting it says the sync did something it
                        // did not.
                        if (bumpDescriptorOrFail(extDiff.targetId(), written.previousVersion(), sourceExt.type(),
                                sourceExt.sourceId(), sourceExt.name(), outcome)) {
                            outcome.updated++;
                        }
                        if (!inDocument) {
                            updates.put(extensionKey, written.uri());
                        }
                        writtenThisRun.put(extDiff.targetId(), written.uri());
                        onTargetBySourceId.put(sourceExt.sourceId(), written.uri());
                        LOGGER.infof("Updated %s '%s' (target=%s, v%d→v%d)",
                                LogSanitizer.sanitize(sourceExt.type()), LogSanitizer.sanitize(sourceExt.name()),
                                LogSanitizer.sanitize(extDiff.targetId()), written.previousVersion(),
                                written.previousVersion() + 1);
                    } else {
                        outcome.failed(sourceExt.sourceId(), sourceExt.type(), sourceExt.name(),
                                "the store did not accept the update");
                    }
                } else if (action == DiffAction.CREATE) {
                    URI created = null;
                    if (inDocument) {
                        // Named by a parser document, which is written after this and
                        // repointed at it — the one referrer a CREATE needs.
                        created = createExtension(sourceExt, "a parser document names it", outcome);
                    } else {
                        if (targetRefs == null) {
                            targetRefs = targetReferences(wfDiff);
                        }
                        WorkflowExtensions.ExtensionRef targetRef = targetRefs.get(extensionKey);
                        if (targetRef != null) {
                            created = healDanglingReference(sourceExt, targetRef, outcome);
                        } else if (adoptionBlocker == null) {
                            // A step the source added. The workflow's own row is an UPDATE
                            // whose diff shows the new step — its steps are compared with
                            // the target's references repointed, so the added step is the
                            // difference — and the adoption about to happen writes the
                            // source's steps, which name this. It is placed there, so it is
                            // not an orphan.
                            //
                            // Refusing these made the ordinary way of working — add a step on
                            // staging, promote — impossible without hand-editing production,
                            // and once a sync had removed a step it could never add it back.
                            created = createExtension(sourceExt, "the source workflow adds a step for it", outcome);
                            if (created != null) {
                                createdForAdoption.add(extensionKey);
                            }
                        } else {
                            // Not created: nothing would place it. The source's steps are not
                            // being adopted, so the new URI would have no reference, and an
                            // unreferenced copy is created again by every later sync.
                            outcome.failed(sourceExt.sourceId(), sourceExt.type(), sourceExt.name(),
                                    "the source adds a step for this " + sourceExt.type()
                                            + ", but there is no step to put it in: " + adoptionBlocker);
                            LOGGER.warnf("Skipped %s '%s': its step cannot be added — %s",
                                    LogSanitizer.sanitize(sourceExt.type()), LogSanitizer.sanitize(sourceExt.name()),
                                    LogSanitizer.sanitize(adoptionBlocker));
                        }
                    }
                    if (created != null) {
                        if (!inDocument) {
                            updates.put(extensionKey, created);
                        }
                        onTargetBySourceId.put(sourceExt.sourceId(), created);
                    }
                }
            } catch (Exception e) {
                LOGGER.warnf(e, "Failed to process extension %s '%s'", LogSanitizer.sanitize(sourceExt.type()),
                        LogSanitizer.sanitize(sourceExt.name()));
                outcome.failed(sourceExt.sourceId(), sourceExt.type(), sourceExt.name(), e);
            }
        }

        return updates;
    }

    /**
     * Why the parser document that names an in-document resource will not be
     * written this run, or null when it will. Mirrors the decisions the loop makes
     * for the document itself, taken before either is written.
     */
    private String documentOwnerBlocker(String extensionKey, WorkflowSourceData sourceWf,
                                        Map<String, ResourceDiff> diffMap, Set<String> selectedSourceIds,
                                        String adoptionBlocker, Map<String, WorkflowExtensions.ExtensionRef> targetRefs) {
        int marker = extensionKey.indexOf(WorkflowExtensions.DOCUMENT_MARKER);
        String ownerKey = marker < 0 ? null : extensionKey.substring(0, marker);
        ExtensionSourceData owner = ownerKey == null ? null : sourceWf.extensions().get(ownerKey);
        ResourceDiff ownerDiff = owner == null ? null : diffMap.get(owner.sourceId());
        if (ownerDiff == null) {
            return "the source did not supply the parser document";
        }
        if (!isSelected(selectedSourceIds, owner.sourceId())) {
            return "the parser document was left out of the selection";
        }
        if (ownerDiff.action() == DiffAction.CONFLICT
                && (selectedSourceIds == null || !selectedSourceIds.contains(owner.sourceId()))) {
            return "the parser document was changed on this instance and is left alone";
        }
        if (ownerDiff.action() == DiffAction.CREATE && !targetRefs.containsKey(ownerKey) && adoptionBlocker != null) {
            return adoptionBlocker;
        }
        return null;
    }

    /**
     * The URI of an existing target resource of this extension type, or null for a
     * type with no store registered.
     */
    private URI targetUriOf(String extensionType, String targetId, int targetVersion) {
        ExtensionStoreOps<?> ops;
        try {
            ops = resolveExtensionOps(extensionType);
        } catch (IllegalArgumentException unregistered) {
            // Reported where the resource is processed, as the wiring error it is;
            // this lookup only feeds the parser repointing and must not preempt that.
            return null;
        }
        return URI.create(ops.resourceUri() + targetId + ops.versionQueryParam() + targetVersion);
    }

    /**
     * The target workflow's references keyed as the matcher keys them; empty when
     * the workflow cannot be read, which leaves every CREATE a plain CREATE.
     */
    private Map<String, WorkflowExtensions.ExtensionRef> targetReferences(ResourceDiff wfDiff) {
        Map<String, WorkflowExtensions.ExtensionRef> refs = new HashMap<>();
        if (wfDiff == null || wfDiff.targetId() == null || wfDiff.targetVersion() == null) {
            return refs;
        }
        try {
            for (WorkflowExtensions.ExtensionRef ref : WorkflowExtensions
                    .scan(workflowStore.readWorkflow(wfDiff.targetId(), wfDiff.targetVersion()))) {
                refs.put(ref.key(), ref);
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not read target workflow %s to look for dangling references: %s",
                    LogSanitizer.sanitize(wfDiff.targetId()), LogSanitizer.sanitize(e.getMessage()));
        }
        return refs;
    }

    /**
     * Recreates a resource the target's workflow step names but the target no
     * longer has, and returns its URI for the step to be repointed at; null, with
     * the reason recorded, when it cannot.
     * <p>
     * The matcher reports such a resource as CREATE, because it could not read the
     * target's copy; the policy above refuses a CREATE, because there is usually no
     * step to wire it into. Here there is one. The common way to get here is a
     * parser document: archives written before parser documents travelled kept the
     * source's parser id in the step, so every agent promoted that way names a
     * parser its own instance never had.
     * <p>
     * Only a resource the store <em>confirms</em> is missing is recreated. A read
     * that failed for any other reason — access, a timeout — would otherwise swap a
     * live resource for a copy and orphan the original.
     */
    private URI healDanglingReference(ExtensionSourceData source, WorkflowExtensions.ExtensionRef targetRef,
                                      Outcome outcome) {
        String couldNotCompare = "the target's " + source.type() + " at " + targetRef.extensionUri()
                + " could not be read, so it was neither compared nor replaced — sync again";
        if (!targetRef.fileExtension().equals(source.type())) {
            outcome.failed(source.sourceId(), source.type(), source.name(), couldNotCompare);
            return null;
        }
        ExtensionStoreOps<Object> ops = resolveExtensionOps(source.type());
        IResourceStore<Object> store = getStore(ops.storeClass());
        try {
            store.read(targetRef.resourceId().getId(), targetRef.resourceId().getVersion());
            // It is there after all: the preview's read of it failed for some other
            // reason, and nothing it said about this resource can be trusted.
            outcome.failed(source.sourceId(), source.type(), source.name(), couldNotCompare);
            return null;
        } catch (IResourceStore.ResourceNotFoundException missing) {
            // The one case this is for — recreate it.
            return createExtension(source, "the target's workflow names " + targetRef.extensionUri()
                    + ", which it does not have", outcome);
        } catch (Exception e) {
            LOGGER.debugf("Could not confirm whether %s is missing: %s",
                    LogSanitizer.sanitize(String.valueOf(targetRef.extensionUri())), LogSanitizer.sanitize(e.getMessage()));
            outcome.failed(source.sourceId(), source.type(), source.name(), couldNotCompare);
            return null;
        }
    }

    /**
     * Creates a resource from the source's content, with its descriptor, and counts
     * it; null, with the failure recorded, when that fails.
     *
     * @param why
     *            what will reference it, for the log
     */
    private URI createExtension(ExtensionSourceData source, String why, Outcome outcome) {
        try {
            ExtensionStoreOps<Object> ops = resolveExtensionOps(source.type());
            IResourceStore<Object> store = getStore(ops.storeClass());
            IResourceId created = store.create(jsonSerialization.deserialize(source.contentJson(), ops.configClass()));
            URI createdUri = URI.create(ops.resourceUri() + created.getId() + ops.versionQueryParam() + created.getVersion());
            DocumentDescriptor descriptor = createDocumentDescriptor(createdUri);
            descriptor.setName(source.name());
            descriptor.setSyncedVersion(created.getVersion());
            documentDescriptorStore.createDescriptor(created.getId(), created.getVersion(),
                    resourceAccessGuard.stampNewDescriptor(descriptor));
            outcome.created++;
            LOGGER.infof("Created %s '%s': %s", LogSanitizer.sanitize(source.type()),
                    LogSanitizer.sanitize(source.name()), LogSanitizer.sanitize(why));
            return createdUri;
        } catch (Exception e) {
            LOGGER.warnf(e, "Failed to create %s '%s'", LogSanitizer.sanitize(source.type()),
                    LogSanitizer.sanitize(source.name()));
            outcome.failed(source.sourceId(), source.type(), source.name(), e);
            return null;
        }
    }

    /**
     * The workflow's extensions with parser documents moved to the end, in their
     * order otherwise. A parser document is repointed at the dictionaries this run
     * writes, so it has to be written after them.
     */
    private static List<Map.Entry<String, ExtensionSourceData>> parsersLast(Map<String, ExtensionSourceData> extensions) {
        List<Map.Entry<String, ExtensionSourceData>> ordered = new ArrayList<>(extensions.size());
        List<Map.Entry<String, ExtensionSourceData>> parsers = new ArrayList<>();
        for (Map.Entry<String, ExtensionSourceData> entry : extensions.entrySet()) {
            (PARSER_EXT.equals(entry.getValue().type()) ? parsers : ordered).add(entry);
        }
        ordered.addAll(parsers);
        return ordered;
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
            case "parser" -> new ExtensionStoreOps<>(
                    ParserConfiguration.class,
                    IParserStore.class,
                    (id, version, config) -> getStore(IRestParserStore.class).updateParser(id, version, config),
                    IRestParserStore.resourceURI,
                    IRestParserStore.versionQueryParam);
            case "regulardictionary" -> new ExtensionStoreOps<>(
                    DictionaryConfiguration.class,
                    IDictionaryStore.class,
                    (id, version, config) -> getStore(IRestDictionaryStore.class).updateRegularDictionary(id, version, config),
                    IRestDictionaryStore.resourceURI,
                    IRestDictionaryStore.versionQueryParam);
            case "behavior" -> new ExtensionStoreOps<>(
                    RuleSetConfiguration.class,
                    IRuleSetStore.class,
                    (id, version, config) -> getStore(IRestRuleSetStore.class).updateRuleSet(id, version, config),
                    IRestRuleSetStore.resourceURI,
                    IRestRuleSetStore.versionQueryParam);
            case "httpcalls" -> new ExtensionStoreOps<>(
                    ApiCallsConfiguration.class,
                    IApiCallsStore.class,
                    (id, version, config) -> getStore(IRestApiCallsStore.class).updateApiCalls(id, version, config),
                    IRestApiCallsStore.resourceURI,
                    IRestApiCallsStore.versionQueryParam);
            case "langchain" -> new ExtensionStoreOps<>(
                    LlmConfiguration.class,
                    ILlmStore.class,
                    (id, version, config) -> getStore(IRestLlmStore.class).updateLlm(id, version, config),
                    IRestLlmStore.resourceURI,
                    IRestLlmStore.versionQueryParam);
            case "property" -> new ExtensionStoreOps<>(
                    PropertySetterConfiguration.class,
                    IPropertySetterStore.class,
                    (id, version, config) -> getStore(IRestPropertySetterStore.class).updatePropertySetter(id, version, config),
                    IRestPropertySetterStore.resourceURI,
                    IRestPropertySetterStore.versionQueryParam);
            case "output" -> new ExtensionStoreOps<>(
                    OutputConfigurationSet.class,
                    IOutputStore.class,
                    (id, version, config) -> getStore(IRestOutputStore.class).updateOutputSet(id, version, config),
                    IRestOutputStore.resourceURI,
                    IRestOutputStore.versionQueryParam);
            case "mcpcalls" -> new ExtensionStoreOps<>(
                    McpCallsConfiguration.class,
                    IMcpCallsStore.class,
                    (id, version, config) -> getStore(IRestMcpCallsStore.class).updateMcpCalls(id, version, config),
                    IRestMcpCallsStore.resourceURI,
                    IRestMcpCallsStore.versionQueryParam);
            case "rag" -> new ExtensionStoreOps<>(
                    RagConfiguration.class,
                    IRagStore.class,
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
     * Holds the configuration class, its stores and the URI pattern for a single
     * extension type. {@code storeClass} is the in-process store, used to confirm a
     * resource is gone and to recreate it — see {@link #healDanglingReference}.
     * <p>
     * The update call is a typed lambda so that dispatch happens here, in the one
     * table, instead of a second switch on the config class's <em>simple name</em>
     * — a string comparison that a class rename would have broken with no compile
     * error.
     */
    private record ExtensionStoreOps<T>(
            Class<T> configClass,
            Class<? extends IResourceStore<T>> storeClass,
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
    private WrittenExtension updateExtension(ExtensionSourceData source, String targetId, Integer targetVersion,
                                             String targetContentJson) {
        ExtensionStoreOps<?> ops = resolveExtensionOps(source.type());
        try {
            String contentJson = restoreRedactedSecrets(source, source.contentJson(), targetContentJson);
            Integer version = targetVersion;
            Response resp;
            try {
                resp = dispatchUpdate(ops, contentJson, targetId, version);
            } catch (WebApplicationException conflict) {
                Integer current = currentVersionFrom(conflict);
                if (current == null || current.equals(version)) {
                    throw conflict;
                }
                // The store knows a version this run did not. Writing the same content
                // against the version it names is the difference between a target that
                // heals itself and one that answers "the store did not accept the
                // update" for ever after a single descriptor write went missing.
                LOGGER.infof("%s '%s' is at v%d, not v%d — retrying the update against the version the store reports",
                        LogSanitizer.sanitize(source.type()), LogSanitizer.sanitize(source.name()), current, version);
                version = current;
                resp = dispatchUpdate(ops, contentJson, targetId, version);
            }
            if (resp == null || resp.getStatus() != 200) {
                return null;
            }
            return new WrittenExtension(
                    URI.create(ops.resourceUri() + targetId + ops.versionQueryParam() + (version + 1)), version);
        } catch (Exception e) {
            LOGGER.warnf(e, "Failed to update %s '%s' (target=%s)", LogSanitizer.sanitize(source.type()), LogSanitizer.sanitize(source.name()),
                    LogSanitizer.sanitize(targetId));
            return null;
        }
    }

    /**
     * An extension this run wrote: its new URI, and the version it was written
     * against.
     * <p>
     * The two can differ from what the preview said — see the conflict retry in
     * {@link #updateExtension} — and the descriptor has to be moved from the
     * version that was actually written, not the one that was planned.
     */
    private record WrittenExtension(URI uri, int previousVersion) {
    }

    /**
     * The version a 409 names as current, or null when this is not that answer.
     * <p>
     * {@code RestVersionInfo.update} refuses a write against a version the resource
     * has moved past, and {@link RestUtilities#createConflictException} puts the
     * resource's real current URI in the body. That is the only authority on the
     * live version that this path can reach — the descriptor, which is what it
     * normally asks, is precisely what may be stale.
     */
    private static Integer currentVersionFrom(WebApplicationException conflict) {
        Response response = conflict.getResponse();
        if (response == null || response.getStatus() != Response.Status.CONFLICT.getStatusCode()) {
            return null;
        }
        Object entity = response.getEntity();
        if (entity == null) {
            return null;
        }
        IResourceId current = RestUtilities.extractResourceId(URI.create(entity.toString()));
        return current == null ? null : current.getVersion();
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
        if (!ScrubbedSecrets.carriesTargetBoundValue(sourceJson)) {
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
     * Creates a workflow the source agent has and the target does not — its
     * resources first, then the workflow itself naming them by their ids on this
     * instance.
     * <p>
     * It used to store the source's workflow document as it was. Every step then
     * named a resource id that exists only on the source, none of those resources
     * were created, and the sync answered 201: the agent version it wrote could not
     * be deployed ("Resource not found"), and no later sync could repair it.
     * <p>
     * All or nothing on the selection: a workflow whose steps name a resource the
     * operator left out has nothing to point that step at, so it is refused before
     * anything is written rather than created half-wired.
     */
    private URI createNewWorkflow(WorkflowSourceData sourceWf, Set<String> selectedSourceIds, Outcome outcome) {
        for (ExtensionSourceData extension : sourceWf.extensions().values()) {
            if (!isSelected(selectedSourceIds, extension.sourceId())) {
                outcome.failed(sourceWf.sourceId(), "workflow", sourceWf.name(),
                        "it was not created: its " + extension.type() + " '" + extension.name()
                                + "' was left out of the selection, and a new workflow needs every resource its steps name");
                return null;
            }
        }

        Map<String, URI> createdByKey = new HashMap<>();
        Map<String, URI> createdBySourceId = new HashMap<>();
        for (Map.Entry<String, ExtensionSourceData> entry : parsersLast(sourceWf.extensions())) {
            ExtensionSourceData extension = entry.getValue();
            if (PARSER_EXT.equals(extension.type())) {
                // Its dictionaries were created just before it; it has to name those.
                extension = new ExtensionSourceData(extension.sourceId(), extension.name(), extension.type(),
                        extension.stepType(), NestedReferences.repointBySourceId(extension.contentJson(), createdBySourceId));
            }
            URI created = createExtension(extension, "the new workflow '" + sourceWf.name() + "' names it", outcome);
            if (created == null) {
                outcome.failed(sourceWf.sourceId(), "workflow", sourceWf.name(),
                        "it was not created, because a resource one of its steps names could not be");
                return null;
            }
            createdBySourceId.put(extension.sourceId(), created);
            if (!WorkflowExtensions.isInDocument(entry.getKey())) {
                createdByKey.put(entry.getKey(), created);
            }
        }

        try {
            WorkflowConfiguration config = jsonSerialization.deserialize(
                    jsonSerialization.serialize(sourceWf.config()), WorkflowConfiguration.class);
            for (WorkflowExtensions.ExtensionRef ref : WorkflowExtensions.scan(config)) {
                URI created = createdByKey.get(ref.key());
                if (created == null) {
                    // The source named it but could not serve it, so there is nothing
                    // here to point the step at.
                    outcome.failed(sourceWf.sourceId(), "workflow", sourceWf.name(),
                            "it was not created: its step at '" + ref.key() + "' names " + ref.extensionUri()
                                    + ", which the source did not supply");
                    return null;
                }
                ref.repointTo(created);
            }

            IWorkflowStore store = CDI.current().select(IWorkflowStore.class).get();
            IResourceId resourceId = store.create(config);
            URI createdUri = RestUtilities.createURI(IRestWorkflowStore.resourceURI, resourceId.getId(),
                    IRestWorkflowStore.versionQueryParam, resourceId.getVersion());

            // Create the DocumentDescriptor that the DocumentDescriptorFilter would
            // normally create on a 201 response — ownership stamp included.
            DocumentDescriptor descriptor = createDocumentDescriptor(createdUri);
            descriptor.setName(sourceWf.name());
            descriptor.setSyncedVersion(resourceId.getVersion());
            documentDescriptorStore.createDescriptor(resourceId.getId(), resourceId.getVersion(),
                    resourceAccessGuard.stampNewDescriptor(descriptor));

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
                                      boolean adoptSourceConfig, Set<String> createdForAdoption,
                                      Outcome outcome) {
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
                refusedAdoption = adoptableSourceConfig(sourceWf.config(), targetRefs, createdForAdoption);
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
                    // A workflow is the one resource a deployment resolves through its
                    // DESCRIPTOR (WorkflowStoreService.getWorkflowDocumentDescriptor), so
                    // pointing the agent at a version whose descriptor did not move
                    // would trade a deployable agent for an undeployable one. The write
                    // stands and is reported; the agent keeps its last resolvable
                    // reference until a later sync repairs the descriptor.
                    if (!bumpDescriptorOrFail(workflowId, workflowVersion, "workflow",
                            sourceWf.sourceId(), sourceWf.name(), outcome)) {
                        return null;
                    }
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
    private String adoptableSourceConfig(WorkflowConfiguration sourceConfig, Map<String, URI> targetRefs,
                                         Set<String> createdForAdoption) {
        for (WorkflowExtensions.ExtensionRef ref : WorkflowExtensions.scan(sourceConfig)) {
            // A reference whose resource this run created for the adoption to place
            // is placed like any other.
            if (!targetRefs.containsKey(ref.key()) && !createdForAdoption.contains(ref.key())) {
                return "the source workflow's steps were not applied: it references a " + ref.fileExtension()
                        + " at '" + ref.key() + "' that the target workflow does not have"
                        + " — add the step to the target workflow, or import the source workflow as a new one";
            }
        }
        return null;
    }

    /**
     * Why the source workflow's steps cannot replace the target's in this run, or
     * null when they can. Mirrors what {@link #adoptableSourceConfig} will check
     * after the extensions are written, but answered before anything is created,
     * from what the run is about to do: every step the source has either names a
     * resource the target already has at that place, or one this run will create
     * because it was served by the source and selected.
     */
    private String adoptionBlocker(WorkflowSourceData sourceWf, ResourceDiff wfDiff, Map<String, ResourceDiff> diffMap,
                                   Set<String> selectedSourceIds) {
        if (!hasSteps(sourceWf.config())) {
            return "the source workflow has no steps";
        }
        Map<String, WorkflowExtensions.ExtensionRef> targetRefs = targetReferences(wfDiff);
        for (WorkflowExtensions.ExtensionRef ref : WorkflowExtensions.scan(sourceWf.config())) {
            if (targetRefs.containsKey(ref.key())) {
                continue;
            }
            ExtensionSourceData added = sourceWf.extensions().get(ref.key());
            if (added == null || diffMap.get(added.sourceId()) == null) {
                return "its step at '" + ref.key() + "' names a " + ref.fileExtension()
                        + " the source instance did not supply";
            }
            if (!isSelected(selectedSourceIds, added.sourceId())) {
                return "its new " + added.type() + " '" + added.name() + "' was left out of the selection";
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
     * <li>Takes the source's agent-level settings, when the preview said they
     * changed and the agent row was selected</li>
     * <li>Replaces workflow URIs with updated versions</li>
     * <li>Takes off the workflows the source no longer has</li>
     * <li>Appends new workflows at specified positions</li>
     * <li>Applies custom workflow order if specified</li>
     * </ul>
     *
     * @param adoptedSettings
     *            the source agent as the preview rewrote it onto this target, or
     *            null to keep the target's own settings
     * @return the agent's new URI, or null when the result is what the agent
     *         already is — nothing is written and no version is burned
     */
    private URI updateAgentConfig(String agentId,
                                  Map<String, URI> updatedWorkflowUris,
                                  List<URI> newWorkflowUris,
                                  Set<String> removedWorkflowIds,
                                  String adoptedSettings,
                                  List<String> workflowOrder,
                                  Outcome outcome) {
        try {
            Integer resolved = resolveLatestVersion(agentId);
            if (resolved == null) {
                // Guessing 1 here wrote against a version the agent had long moved
                // past: a 409 AFTER every extension and workflow had already been
                // written, reported as a 500, with nothing rolled back. If the version
                // cannot be established, say so instead of writing.
                throw new IllegalStateException("the current version of agent " + agentId
                        + " could not be established from its descriptor, so it was not written");
            }
            int currentVersion = resolved;
            AgentConfiguration current = agentStore.readAgent(agentId, currentVersion);

            // Replace workflow URIs with updated versions, and take off the ones the
            // source no longer has
            List<URI> workflows = new ArrayList<>();
            for (URI workflow : current.getWorkflows() != null ? current.getWorkflows() : List.<URI>of()) {
                IResourceId wfResId = RestUtilities.extractResourceId(workflow);
                if (wfResId != null && removedWorkflowIds.contains(wfResId.getId())) {
                    continue;
                }
                workflows.add(wfResId != null && updatedWorkflowUris.containsKey(wfResId.getId())
                        ? updatedWorkflowUris.get(wfResId.getId())
                        : workflow);
            }

            // Append new workflows
            workflows.addAll(newWorkflowUris);

            // Apply custom workflow order if specified
            if (workflowOrder != null && !workflowOrder.isEmpty()) {
                workflows = reorderWorkflows(workflows, workflowOrder);
            }

            List<URI> originalWorkflows = current.getWorkflows() != null ? List.copyOf(current.getWorkflows()) : List.of();
            AgentConfiguration adopted = adoptedSettings != null
                    ? jsonSerialization.deserialize(adoptedSettings, AgentConfiguration.class)
                    : null;
            boolean settingsChanged = false;
            if (adopted != null) {
                // Bound to this instance whatever the source says — see
                // StructuralMatcher.adoptableAgentJson.
                adopted.setIdentity(current.getIdentity());
                adopted.setWorkflows(originalWorkflows);
                String adoptedJson = jsonSerialization.serialize(adopted);
                settingsChanged = adoptedJson == null || !adoptedJson.equals(jsonSerialization.serialize(current));
            }
            if (!settingsChanged && workflows.equals(originalWorkflows)) {
                // Everything that could have changed did not — e.g. a new workflow
                // whose creation failed was the only difference. Writing would burn a
                // version to say nothing.
                return null;
            }
            AgentConfiguration agentConfig = settingsChanged ? adopted : current;
            agentConfig.setWorkflows(workflows);

            // Update the agent
            Response resp = agentStore.updateAgent(agentId, currentVersion, agentConfig);
            if (resp != null && resp.getStatus() == 200) {
                bumpDescriptorOrFail(agentId, currentVersion, "agent", agentId, null, outcome);
                URI updatedUri = URI.create(IRestAgentStore.resourceURI + agentId + "?version=" + (currentVersion + 1));
                LOGGER.infof("Agent '%s' upgraded successfully (v%d→v%d)", LogSanitizer.sanitize(agentId), currentVersion,
                        currentVersion + 1);
                return updatedUri;
            }

            outcome.failed(agentId, "agent", null, "the agent store did not accept the updated agent");
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
     * <p>
     * Resolved through {@link IDocumentDescriptorStore#readCurrentDescriptor},
     * never {@code readDescriptor(id, null)}: the descriptor store is historized
     * and its read requires a version, so passing null always threw and this method
     * always answered null. {@link #readLatestVersion} then substituted 1, so every
     * upgrade after the first one read and wrote the target's <em>version 1</em> —
     * the store refused it, and the sync reported "the store did not accept the
     * update" forever. See {@link StructuralMatcher}'s counterpart for the matching
     * half of the same defect.
     */
    private Integer resolveLatestVersion(String resourceId) {
        try {
            // The store is the authority; the descriptor is a projection of it, and
            // the two disagree exactly when something has gone wrong — which is when
            // writing against the right version matters most.
            IResourceId current = agentStore.getCurrentResourceId(resourceId);
            if (current != null && current.getVersion() != null) {
                return current.getVersion();
            }
        } catch (Exception e) {
            LOGGER.debugf("Store could not name the current version of %s: %s",
                    LogSanitizer.sanitize(resourceId), LogSanitizer.sanitize(e.getMessage()));
        }
        try {
            DocumentDescriptor desc = documentDescriptorStore.readCurrentDescriptor(resourceId);
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

    // ==================== Descriptor Bookkeeping ====================

    /**
     * Moves a resource's {@link DocumentDescriptor} onto the version this run just
     * wrote.
     * <p>
     * Every other write path in EDDI gets this for free: a {@code PUT} through the
     * HTTP API passes {@link DocumentDescriptorFilter}, which bumps the descriptor
     * on the way out. An upgrade calls the same stores <em>in-process</em>, through
     * CDI proxies, so no JAX-RS filter ever runs and the descriptors stayed behind
     * on the version the resources had before the sync.
     * <p>
     * That is not cosmetic. The descriptor is what
     * {@link WorkflowStoreService#getWorkflowDocumentDescriptor} reads when an
     * agent is deployed, so a synced agent version could be written successfully
     * and then refuse to deploy with "Resource not found", and it is what
     * {@link #resolveLatestVersion} reads, so the next sync aimed at the old
     * version. It is also the row the Manager lists, so the UI kept showing the
     * pre-sync version.
     *
     * @throws Exception
     *             when the descriptor cannot be moved — deliberately propagated so
     *             the caller records a failure. A resource whose descriptor was not
     *             bumped is written but unreachable, which is precisely the state
     *             this method exists to prevent, and reporting success for it is
     *             how the original defect stayed invisible.
     */
    private void bumpDescriptor(String resourceId, int previousVersion) throws Exception {
        DocumentDescriptor descriptor = documentDescriptorStore.readDescriptor(resourceId, previousVersion);
        if (descriptor == null) {
            throw new IllegalStateException("No descriptor for " + resourceId + " at version " + previousVersion);
        }
        descriptor.setLastModifiedOn(new Date());
        descriptor.setResource(withVersion(descriptor.getResource(), previousVersion + 1));
        // The baseline a later sync measures local edits against: this version came
        // from the source, so a version past it was made here.
        descriptor.setSyncedVersion(previousVersion + 1);
        // Mirrors DocumentDescriptorFilter: carrying the descriptor object forward
        // already carries ownership, and rebuilding lets a descriptor written before
        // the access index existed acquire one the first time it is touched.
        DescriptorAccess.rebuildIndex(descriptor);
        documentDescriptorStore.updateDescriptor(resourceId, previousVersion, descriptor);
    }

    /**
     * Same as {@link #bumpDescriptor}, but records a failure instead of throwing.
     *
     * @return true when the descriptor now names the new version
     */
    private boolean bumpDescriptorOrFail(String resourceId, int previousVersion, String resourceType,
                                         String sourceId, String name, Outcome outcome) {
        try {
            bumpDescriptor(resourceId, previousVersion);
            return true;
        } catch (Exception e) {
            LOGGER.warnf(e, "Wrote %s '%s' but could not move its descriptor to v%d",
                    LogSanitizer.sanitize(resourceType), LogSanitizer.sanitize(resourceId), previousVersion + 1);
            outcome.failed(sourceId, resourceType, name,
                    "the new version was written but its descriptor still names v" + previousVersion
                            + ", so the deployment cannot load it: " + e.getMessage());
            return false;
        }
    }

    /**
     * The same resource URI, carrying {@code version}. Mirrors
     * {@code DocumentDescriptorFilter.createNewVersionOfResource} — a descriptor's
     * resource URI is how every reader learns which version is current.
     */
    private static URI withVersion(URI resource, int version) {
        if (resource == null) {
            return null;
        }
        String uri = resource.toString();
        uri = uri.contains("version=")
                ? uri.substring(0, uri.lastIndexOf('=') + 1) + version
                : uri + "?version=" + version;
        return URI.create(uri);
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
