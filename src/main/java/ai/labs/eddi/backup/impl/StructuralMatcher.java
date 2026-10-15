/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource;
import ai.labs.eddi.backup.IResourceSource.*;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.*;

/**
 * Matches source resources (from ZIP, remote API, or local agent) against a
 * target agent's resource tree by structural position and type. Produces an
 * {@link ImportPreview} with content diffs.
 * <p>
 * <b>Matching algorithm:</b>
 * <ol>
 * <li><b>Agent</b> — matched directly by {@code targetAgentId} parameter</li>
 * <li><b>Workflows</b> — matched by position (index in agent's workflow
 * list)</li>
 * <li><b>Extensions</b> — matched by the canonical
 * {@link WorkflowExtensions#scan(WorkflowConfiguration) extension key}: the
 * workflow step's {@code type} URI plus that type's occurrence ordinal within
 * the workflow</li>
 * <li><b>Snippets</b> — matched by {@code PromptSnippet.name} (natural
 * key)</li>
 * </ol>
 * <p>
 * This is the core matching engine shared by all import/sync flows. It is
 * stateless — all state comes from the {@link IResourceSource} and the target
 * agent's existing configuration.
 * <p>
 * <b>Content is always loaded when there is a target.</b>
 * {@code includeContent} decides only whether the source/target JSON is
 * <em>returned</em> for the diff view — never whether it is read. Deciding the
 * {@link DiffAction} from content that was not loaded made every matched
 * resource compare equal, so {@link UpgradeExecutor} skipped all of them and a
 * sync silently updated nothing.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class StructuralMatcher {

    private static final Logger LOGGER = Logger.getLogger(StructuralMatcher.class);

    private final IRestAgentStore agentStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IRestPromptSnippetStore snippetStore;
    private final IRestWorkflowStore workflowStore;
    private final IRestInterfaceFactory restInterfaceFactory;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public StructuralMatcher(IRestAgentStore agentStore,
            IDocumentDescriptorStore documentDescriptorStore,
            IRestPromptSnippetStore snippetStore,
            IRestWorkflowStore workflowStore,
            IRestInterfaceFactory restInterfaceFactory,
            IJsonSerialization jsonSerialization) {
        this.agentStore = agentStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.snippetStore = snippetStore;
        this.workflowStore = workflowStore;
        this.restInterfaceFactory = restInterfaceFactory;
        this.jsonSerialization = jsonSerialization;
    }

    /**
     * Build a preview of what an import/sync would do.
     *
     * @param source
     *            the resource source (ZIP, remote, local)
     * @param targetAgentId
     *            if non-null, match against this agent's resource tree (upgrade
     *            strategy). If null, all resources are CREATE.
     * @param includeContent
     *            if true, populate sourceContent/targetContent for the diff view.
     *            Does not affect which {@link DiffAction} is computed — content is
     *            always loaded when {@code targetAgentId} is given.
     * @return the preview with all resource diffs
     * @throws jakarta.ws.rs.NotFoundException
     *             if {@code targetAgentId} was given but no such agent exists
     * @throws jakarta.ws.rs.InternalServerErrorException
     *             if the target agent exists but could not be read
     */
    public ImportPreview buildPreview(IResourceSource source,
                                      String targetAgentId,
                                      boolean includeContent) {
        AgentSourceData sourceAgent = source.readAgent();
        List<WorkflowSourceData> sourceWorkflows = source.readWorkflows();
        List<SnippetSourceData> sourceSnippets = source.readSnippets();

        String targetAgentName = null;
        AgentConfiguration targetConfig = null;
        List<ResourceDiff> diffs = new ArrayList<>();

        if (targetAgentId != null) {
            // Deliberately not caught: the caller explicitly named a target, so a
            // target that cannot be read is an error the operator can act on — not a
            // silent switch to "create everything", which previewed an upgrade as a
            // full duplicate of the agent and gave the operator nothing to
            // distinguish the two. See readTargetAgent for 404 vs 5xx.
            targetConfig = readTargetAgent(targetAgentId);
            targetAgentName = readDescriptorName(targetAgentId);
        }

        // 1. Agent-level diff
        diffs.add(buildAgentDiff(sourceAgent, targetAgentId, targetConfig, includeContent));

        // 2. Workflow diffs (matched by position)
        List<URI> targetWorkflowUris = targetConfig != null && targetConfig.getWorkflows() != null
                ? targetConfig.getWorkflows()
                : List.of();
        for (WorkflowSourceData sourceWf : sourceWorkflows) {
            int idx = sourceWf.positionIndex();
            if (idx < targetWorkflowUris.size()) {
                // Matched by position
                diffs.addAll(buildMatchedWorkflowDiffs(sourceWf, targetWorkflowUris.get(idx), includeContent));
            } else {
                // New workflow — no target match
                diffs.addAll(buildUnmatchedWorkflowDiffs(sourceWf));
            }
        }

        // 2b. Workflows the target has past the end of the source's list. Counted
        // against the source agent's own list rather than the workflows that were
        // read, so one the source failed to serve is never mistaken for a removal.
        int sourceWorkflowCount = sourceAgent.config() != null && sourceAgent.config().getWorkflows() != null
                ? sourceAgent.config().getWorkflows().size()
                : 0;
        for (int i = sourceWorkflowCount; i < targetWorkflowUris.size(); i++) {
            ResourceDiff removed = buildRemovedWorkflowDiff(targetWorkflowUris.get(i), i, includeContent);
            if (removed != null) {
                diffs.add(removed);
            }
        }

        // 3. Snippet diffs (matched by name)
        Map<String, IResourceId> existingSnippetsByName = buildExistingSnippetNameMap();
        for (SnippetSourceData sourceSnippet : sourceSnippets) {
            diffs.add(buildSnippetDiff(sourceSnippet, existingSnippetsByName, includeContent));
        }

        return new ImportPreview(
                sourceAgent.sourceId(),
                sourceAgent.name(),
                targetAgentId,
                targetAgentName,
                diffs,
                null,
                source.warnings());
    }

    // ==================== Agent Diff ====================

    private ResourceDiff buildAgentDiff(AgentSourceData sourceAgent,
                                        String targetAgentId,
                                        AgentConfiguration targetConfig,
                                        boolean includeContent) {
        if (targetAgentId == null) {
            return new ResourceDiff(
                    sourceAgent.sourceId(), "agent", sourceAgent.name(),
                    DiffAction.CREATE, null, null, null,
                    includeContent ? serializeSafe(sourceAgent.config()) : null,
                    null, -1);
        }

        String targetJson = serializeSafe(targetConfig);
        String sourceJson = secretNeutral(adoptableAgentJson(sourceAgent.config(), targetConfig), targetJson);

        // The same authority the content was read at, so the row states the version
        // the operator is actually upgrading from.
        Integer targetVersion = currentVersionOf(targetAgentId);

        // Agent-level settings are written now, so a hotfix to them here (a HITL
        // gate, a capability) is guarded like any other resource's. The synced
        // version is rewritten onto today's workflows first: an extension edited in
        // the Manager moves the agent's workflow URIs, and that is not an edit to
        // the agent's own settings.
        DiffAction action;
        if (contentEquals(withoutStoreOwnedAgentFields(sourceJson), withoutStoreOwnedAgentFields(targetJson))) {
            action = DiffAction.SKIP;
        } else if (targetVersion != null && changedLocally(targetAgentId, targetVersion, targetJson,
                version -> adoptableAgentJson(agentStore.readAgent(targetAgentId, version), targetConfig))) {
            action = DiffAction.CONFLICT;
        } else {
            action = DiffAction.UPDATE;
        }

        return new ResourceDiff(
                sourceAgent.sourceId(), "agent", sourceAgent.name(),
                action, targetAgentId, targetVersion, "targetAgent",
                includeContent ? sourceJson : null,
                includeContent ? targetJson : null, -1);
    }

    /**
     * The source agent's configuration as a sync would write it onto the target:
     * the source's settings, with every workflow the target already has at that
     * position named by the target's own URI, and the target's {@code identity} and
     * {@code compatibilityGeneration}.
     * <p>
     * Comparing the raw source instead made every agent read as changed on every
     * sync — its workflow URIs name the source instance's ids, which the target
     * never has — so the preview showed an agent UPDATE that the executor then
     * (rightly) did not write. The identity is the target's because it is bound to
     * this instance: its private key lives in this instance's vault, and a copied
     * public key would describe a key pair nobody here holds.
     *
     * @return the JSON to compare and write, or the raw source when it cannot be
     *         rewritten
     */
    String adoptableAgentJson(AgentConfiguration source, AgentConfiguration target) {
        String raw = serializeSafe(source);
        if (raw == null || target == null) {
            return raw;
        }
        try {
            AgentConfiguration copy = jsonSerialization.deserialize(raw, AgentConfiguration.class);
            if (copy == null) {
                return raw;
            }
            List<URI> sourceWorkflows = copy.getWorkflows() != null ? new ArrayList<>(copy.getWorkflows()) : new ArrayList<>();
            List<URI> targetWorkflows = target.getWorkflows() != null ? target.getWorkflows() : List.of();
            for (int i = 0; i < sourceWorkflows.size() && i < targetWorkflows.size(); i++) {
                sourceWorkflows.set(i, targetWorkflows.get(i));
            }
            copy.setWorkflows(sourceWorkflows);
            copy.setIdentity(target.getIdentity());
            // Numbered by each instance's own store, which assigns it on every write:
            // the source's value says nothing about this agent here.
            copy.setCompatibilityGeneration(target.getCompatibilityGeneration());
            return serializeSafe(copy);
        } catch (Exception e) {
            LOGGER.debugf("Could not rewrite the source agent onto the target's workflows: %s", e.getMessage());
            return raw;
        }
    }

    // ==================== Workflow Diffs ====================

    private List<ResourceDiff> buildMatchedWorkflowDiffs(WorkflowSourceData sourceWf,
                                                         URI targetWorkflowUri,
                                                         boolean includeContent) {
        List<ResourceDiff> diffs = new ArrayList<>();

        IResourceId targetResId = RestUtilities.extractResourceId(targetWorkflowUri);
        if (targetResId == null) {
            diffs.addAll(buildUnmatchedWorkflowDiffs(sourceWf));
            return diffs;
        }

        String targetId = targetResId.getId();
        int targetVersion = targetResId.getVersion();
        String targetName = readDescriptorName(targetId);

        // Extension diffs within this workflow — matched by the canonical
        // WorkflowExtensions key, which both sides derive the same way.
        Map<String, ExtensionSourceData> sourceExtensions = sourceWf.extensions();
        Map<String, TargetExtension> targetExtensions = readTargetExtensions(targetId, targetVersion);

        // Workflow-level diff, on the source's steps pointed at the target's own
        // resources. Compared as written, the two name each other's ids and never
        // agree, so every workflow read as changed on every sync even when its
        // pipeline was identical.
        String sourceJson = repointedWorkflowJson(sourceWf.config(), targetExtensions);
        String targetJson = readTargetWorkflowJson(targetId, targetVersion);
        // A step added or reconfigured here would be dropped by adopting the source's
        // steps, so a structural edit on this instance is a CONFLICT too. The synced
        // version's steps are pointed at today's resources before comparing: an
        // extension edited in the Manager moves a step's URI, and that is not a
        // change to the pipeline.
        DiffAction wfAction;
        if (contentEquals(sourceJson, targetJson)) {
            wfAction = DiffAction.SKIP;
        } else if (changedLocally(targetId, targetVersion, targetJson,
                version -> repointedWorkflowJson(workflowStore.readWorkflow(targetId, version), targetExtensions))) {
            wfAction = DiffAction.CONFLICT;
        } else {
            wfAction = DiffAction.UPDATE;
        }

        diffs.add(new ResourceDiff(
                sourceWf.sourceId(), "workflow",
                sourceWf.name() != null ? sourceWf.name() : targetName,
                wfAction, targetId, targetVersion, "position",
                includeContent ? sourceJson : null,
                includeContent ? targetJson : null, sourceWf.positionIndex()));

        // Steps the target has and the source no longer does. They leave with the
        // adoption of the source's steps — an UPDATE, or a CONFLICT the operator
        // chooses to overwrite. Only the
        // workflow's own references are listed: a dictionary a parser document
        // stops naming shows in that parser's diff, and a reference the source
        // still makes but could not serve is not a removal.
        if (wfAction != DiffAction.SKIP) {
            Set<String> sourceKeys = new HashSet<>(sourceExtensions.keySet());
            WorkflowExtensions.scan(sourceWf.config()).forEach(ref -> sourceKeys.add(ref.key()));
            for (Map.Entry<String, TargetExtension> entry : targetExtensions.entrySet()) {
                TargetExtension removed = entry.getValue();
                if (WorkflowExtensions.isInDocument(entry.getKey()) || sourceKeys.contains(entry.getKey())) {
                    continue;
                }
                diffs.add(new ResourceDiff(
                        removed.id, removed.type, readDescriptorName(removed.id),
                        DiffAction.REMOVE, removed.id, removed.version, "type",
                        null, includeContent ? removed.contentJson : null, -1));
            }
        }

        // Where each matched source resource lives on the target. A parser document
        // names its dictionaries by id, and those ids differ between instances by
        // construction; compared as written, every parser that names one would read
        // as changed on every sync.
        Map<String, URI> onTarget = new HashMap<>();
        for (Map.Entry<String, ExtensionSourceData> entry : sourceExtensions.entrySet()) {
            TargetExtension targetExt = targetExtensions.get(entry.getKey());
            URI uri = targetExt == null
                    ? null
                    : WorkflowExtensions.resourceUri(entry.getValue().type(), targetExt.id, targetExt.version);
            if (uri != null) {
                onTarget.put(entry.getValue().sourceId(), uri);
            }
        }

        List<ResourceDiff> extensionDiffs = new ArrayList<>();
        Set<String> changing = new HashSet<>();
        for (Map.Entry<String, ExtensionSourceData> entry : sourceExtensions.entrySet()) {
            String extensionKey = entry.getKey();
            ExtensionSourceData sourceExt = entry.getValue();
            TargetExtension targetExt = targetExtensions.get(extensionKey);

            if (targetExt != null) {
                // Matched by step type + occurrence
                String tgtContent = targetExt.contentJson;
                String srcContent = AbstractBackupService.PARSER_EXT.equals(sourceExt.type())
                        ? NestedReferences.repointBySourceId(sourceExt.contentJson(), onTarget)
                        : sourceExt.contentJson();
                String comparable = secretNeutral(srcContent, tgtContent);
                DiffAction extAction;
                if (contentEquals(comparable, tgtContent)) {
                    extAction = DiffAction.SKIP;
                } else {
                    extAction = changedLocally(targetExt.id, targetExt.version, tgtContent,
                            version -> serializeSafe(readTypedExtension(targetExt.authority(), targetExt.id, version)))
                                    ? DiffAction.CONFLICT
                                    : DiffAction.UPDATE;
                }

                // The content shown is what would be written — secrets already put
                // back from the target — so the diff never shows a placeholder or
                // the source's own vault reference as a change.
                extensionDiffs.add(new ResourceDiff(
                        sourceExt.sourceId(), sourceExt.type(), sourceExt.name(),
                        extAction, targetExt.id, targetExt.version, "type",
                        includeContent ? comparable : null,
                        includeContent ? tgtContent : null, -1));
                if (extAction == DiffAction.UPDATE) {
                    changing.add(sourceExt.sourceId());
                }
            } else {
                // No match — new extension type in this workflow
                extensionDiffs.add(new ResourceDiff(
                        sourceExt.sourceId(), sourceExt.type(), sourceExt.name(),
                        DiffAction.CREATE, null, null, null,
                        includeContent ? sourceExt.contentJson() : null,
                        null, -1));
                changing.add(sourceExt.sourceId());
            }
        }

        // A parser document unchanged in itself still has to be written when a
        // dictionary it names is: it names that dictionary by version, and the sync
        // moves the dictionary to a new one. Saying SKIP here and writing it anyway
        // would make the result disagree with the preview the operator approved.
        for (ResourceDiff diff : extensionDiffs) {
            ExtensionSourceData sourceExt = findBySourceId(sourceExtensions, diff.sourceId());
            if (diff.action() == DiffAction.SKIP && sourceExt != null
                    && AbstractBackupService.PARSER_EXT.equals(sourceExt.type())
                    && NestedReferences.namesAny(sourceExt.contentJson(), changing)) {
                diff = new ResourceDiff(diff.sourceId(), diff.resourceType(), diff.name(), DiffAction.UPDATE,
                        diff.targetId(), diff.targetVersion(), diff.matchStrategy(), diff.sourceContent(),
                        diff.targetContent(), diff.workflowIndex());
            }
            diffs.add(diff);
        }

        return diffs;
    }

    private static ExtensionSourceData findBySourceId(Map<String, ExtensionSourceData> extensions, String sourceId) {
        for (ExtensionSourceData extension : extensions.values()) {
            if (extension.sourceId().equals(sourceId)) {
                return extension;
            }
        }
        return null;
    }

    private List<ResourceDiff> buildUnmatchedWorkflowDiffs(WorkflowSourceData sourceWf) {
        List<ResourceDiff> diffs = new ArrayList<>();

        // Workflow itself is CREATE
        diffs.add(new ResourceDiff(
                sourceWf.sourceId(), "workflow", sourceWf.name(),
                DiffAction.CREATE, null, null, null,
                null, null, sourceWf.positionIndex()));

        // All extensions are CREATE
        for (ExtensionSourceData ext : sourceWf.extensions().values()) {
            diffs.add(new ResourceDiff(
                    ext.sourceId(), ext.type(), ext.name(),
                    DiffAction.CREATE, null, null, null,
                    null, null, -1));
        }

        return diffs;
    }

    /**
     * The source workflow's JSON with every reference the target also has pointed
     * at the target's copy, exactly as the target workflow names it. What is left
     * different is what a sync would actually change: a step added, removed,
     * reordered or reconfigured.
     */
    private String repointedWorkflowJson(WorkflowConfiguration source, Map<String, TargetExtension> targetExtensions) {
        String raw = serializeSafe(source);
        if (raw == null) {
            return null;
        }
        try {
            WorkflowConfiguration copy = jsonSerialization.deserialize(raw, WorkflowConfiguration.class);
            if (copy == null) {
                return raw;
            }
            for (WorkflowExtensions.ExtensionRef ref : WorkflowExtensions.scan(copy)) {
                TargetExtension counterpart = targetExtensions.get(ref.key());
                if (counterpart != null && counterpart.uri != null) {
                    ref.repointTo(counterpart.uri);
                }
            }
            return serializeSafe(copy);
        } catch (Exception e) {
            LOGGER.debugf("Could not repoint the source workflow onto the target's resources: %s", e.getMessage());
            return raw;
        }
    }

    /**
     * A REMOVE row for a workflow the target agent has past the end of the source
     * agent's list, or null when the URI names no resource.
     */
    private ResourceDiff buildRemovedWorkflowDiff(URI targetWorkflowUri, int position, boolean includeContent) {
        IResourceId resId = RestUtilities.extractResourceId(targetWorkflowUri);
        if (resId == null || resId.getId() == null) {
            return null;
        }
        return new ResourceDiff(
                resId.getId(), "workflow", readDescriptorName(resId.getId()),
                DiffAction.REMOVE, resId.getId(), resId.getVersion(), "position",
                null, includeContent ? readTargetWorkflowJson(resId.getId(), resId.getVersion()) : null, position);
    }

    /** Reads one version of a target resource as JSON; throws when it cannot. */
    @FunctionalInterface
    private interface VersionReader {
        String read(int version) throws Exception;
    }

    /**
     * Whether the target's copy carries changes made on this instance that a sync
     * would overwrite: its version is past the one its descriptor records as
     * synced, and it differs from that synced version in something other than its
     * own secrets.
     * <p>
     * The secrets exception is what makes this usable. Setting production's own API
     * key right after a first promotion is <em>the</em> expected local edit, and a
     * sync never overwrites it — credentials and vault references are the target's
     * (see {@link ScrubbedSecrets}). Counting it would have turned every later
     * change to that resource into a CONFLICT that a sync of everything leaves
     * alone.
     * <p>
     * A resource with no recorded baseline (created here, or written before the
     * baseline existed) is never reported: there is nothing to tell a local edit
     * from the version a sync wrote, and a CONFLICT that cannot be justified would
     * make every sync of such an agent stop and ask. A baseline version that cannot
     * be read is reported — the edit is certain, only its extent is not.
     *
     * @param currentJson
     *            the target's current content, as compared against the source
     */
    private boolean changedLocally(String resourceId, int version, String currentJson, VersionReader readVersion) {
        Integer synced;
        try {
            DocumentDescriptor descriptor = documentDescriptorStore.readDescriptor(resourceId, version);
            synced = descriptor != null ? descriptor.getSyncedVersion() : null;
        } catch (Exception e) {
            LOGGER.debugf("Could not read the sync baseline of %s v%d: %s", LogSanitizer.sanitize(resourceId), version,
                    LogSanitizer.sanitize(e.getMessage()));
            return false;
        }
        if (synced == null || version <= synced) {
            return false;
        }
        try {
            String baseline = readVersion.read(synced);
            if (baseline == null || currentJson == null) {
                return true;
            }
            // The synced version with the target's current secrets put back: what is
            // still different from the current version was changed by hand here.
            return !contentEquals(secretNeutral(baseline, currentJson), currentJson);
        } catch (Exception e) {
            LOGGER.debugf("Could not read %s v%d to compare a local edit against: %s", LogSanitizer.sanitize(resourceId),
                    synced, LogSanitizer.sanitize(e.getMessage()));
            return true;
        }
    }

    // ==================== Snippet Diffs ====================

    private ResourceDiff buildSnippetDiff(SnippetSourceData sourceSnippet,
                                          Map<String, IResourceId> existingByName,
                                          boolean includeContent) {
        IResourceId existing = existingByName.get(sourceSnippet.name());

        if (existing != null) {
            String targetJson = readTargetSnippetJson(existing.getId(), existing.getVersion());
            String sourceJson = secretNeutral(serializeSafe(sourceSnippet.snippet()), targetJson);
            DiffAction action;
            if (contentEquals(sourceJson, targetJson)) {
                action = DiffAction.SKIP;
            } else {
                action = changedLocally(existing.getId(), existing.getVersion(), targetJson,
                        version -> serializeSafe(snippetStore.readSnippet(existing.getId(), version)))
                                ? DiffAction.CONFLICT
                                : DiffAction.UPDATE;
            }

            return new ResourceDiff(
                    sourceSnippet.sourceId(), "snippet", sourceSnippet.name(),
                    action, existing.getId(), existing.getVersion(), "name",
                    includeContent ? sourceJson : null,
                    includeContent ? targetJson : null, -1);
        }

        return new ResourceDiff(
                sourceSnippet.sourceId(), "snippet", sourceSnippet.name(),
                DiffAction.CREATE, null, null, null,
                includeContent ? serializeSafe(sourceSnippet.snippet()) : null,
                null, -1);
    }

    // ==================== Target Reading Helpers ====================

    /**
     * Reads the agent the caller named as the sync target.
     * <p>
     * A missing agent is a 404 — the operator mistyped an id, or the agent was
     * deleted. Everything else is a 5xx: reporting a datastore outage as "target
     * agent not found" sends the operator to look for a resource that is there, and
     * a client that retries a 404 by creating the agent would duplicate it. The two
     * are distinguished by the store's own contract, which separates
     * {@link IResourceStore.ResourceNotFoundException} from
     * {@link IResourceStore.ResourceStoreException}.
     */
    private AgentConfiguration readTargetAgent(String agentId) {
        AgentConfiguration config;
        try {
            Integer version = currentVersionOf(agentId);
            if (version == null && !targetAgentExists(agentId)) {
                // Neither the store nor the descriptor knows the id: a mistyped or
                // deleted target. That is a 404 the operator can act on — it used to
                // fall through to the 500 below, which also reached them with no body.
                throw new NotFoundException("Target agent " + agentId + " does not exist.");
            }
            if (version == null) {
                // Falling back to 1 here previewed VERSION 1 of a target that may be
                // at any version — the operator saw pre-sync content labelled
                // "target", and the executor then wrote snippets, extensions and
                // workflows before the agent write finally refused the unknown
                // version, leaving those partial versions behind. An unresolvable
                // target is reported instead of guessed at.
                throw new InternalServerErrorException("Could not establish the current version of target agent "
                        + agentId + ", so there is nothing to compare against.");
            }
            config = agentStore.readAgent(agentId, version);
        } catch (NotFoundException | ForbiddenException e) {
            // A caller without VIEW on the target is refused (403), not handed a 500
            // that reads like a server fault.
            throw e;
        } catch (Exception e) {
            // instanceof rather than a second catch clause: the store's checked
            // exceptions reach this frame through SneakyThrow (see
            // RestVersionInfo.read), so the compiler does not believe they can be
            // thrown here and refuses to let them be caught by type.
            if (e instanceof IResourceStore.ResourceNotFoundException) {
                throw new NotFoundException("Target agent " + agentId + " does not exist: " + e.getMessage(), e);
            }
            throw new InternalServerErrorException(
                    "Could not read target agent " + agentId + ": " + e.getMessage(), e);
        }
        if (config == null) {
            throw new NotFoundException("Target agent " + agentId + " does not exist.");
        }
        return config;
    }

    private String readTargetWorkflowJson(String workflowId, int version) {
        try {
            WorkflowConfiguration config = workflowStore.readWorkflow(workflowId, version);
            return serializeSafe(config);
        } catch (Exception e) {
            LOGGER.warnf(e, "Could not read target workflow %s v%d", workflowId, version);
            return null;
        }
    }

    /**
     * One resource the target workflow references.
     *
     * @param uri
     *            the reference exactly as the target workflow holds it — what a
     *            repointed source step must equal for the two to compare equal
     * @param type
     *            the file-extension label, for a REMOVE row
     */
    private record TargetExtension(String id, int version, String contentJson, URI uri, String type) {

        /** The store authority the resource lives in, e.g. {@code ai.labs.llm}. */
        String authority() {
            WorkflowExtensions.ExtensionType extensionType = WorkflowExtensions.typeOf(uri);
            return extensionType != null ? extensionType.resourceAuthority() : null;
        }
    }

    private void readTargetExtension(WorkflowExtensions.ExtensionRef ref, Map<String, TargetExtension> into) {
        try {
            Object extConfig = readTypedExtension(ref.type().resourceAuthority(), ref.resourceId().getId(),
                    ref.resourceId().getVersion());
            String json = serializeSafe(extConfig);
            into.put(ref.key(), new TargetExtension(
                    ref.resourceId().getId(), ref.resourceId().getVersion(), json, ref.extensionUri(),
                    ref.fileExtension()));
        } catch (Exception e) {
            LOGGER.warnf(e, "Could not read target extension %s", ref.extensionUri());
        }
    }

    /**
     * Reads all extensions a target workflow references, keyed by the canonical
     * {@link WorkflowExtensions} key. The URI of each extension is read from the
     * step's {@code config} map, which is where the engine itself looks — reading
     * it from {@code extensions} found nothing on any real workflow, so every
     * source extension was reported as CREATE and a sync duplicated the lot.
     */
    private Map<String, TargetExtension> readTargetExtensions(String workflowId, int version) {
        Map<String, TargetExtension> result = new LinkedHashMap<>();

        WorkflowConfiguration wfConfig;
        try {
            wfConfig = workflowStore.readWorkflow(workflowId, version);
        } catch (Exception e) {
            LOGGER.warnf(e, "Could not read target workflow config %s v%d", workflowId, version);
            return result;
        }

        List<WorkflowExtensions.ExtensionRef> refs = WorkflowExtensions.scan(wfConfig);
        for (WorkflowExtensions.ExtensionRef ref : refs) {
            readTargetExtension(ref, result);
        }

        // The dictionaries the target's parser documents name themselves, keyed the
        // way the source keys them. One the workflow already references is read once,
        // under the workflow's key.
        Set<String> read = new HashSet<>();
        result.values().forEach(extension -> read.add(extension.id));
        for (WorkflowExtensions.ExtensionRef ref : refs) {
            TargetExtension document = result.get(ref.key());
            if (document == null || !AbstractBackupService.PARSER_EXT.equals(ref.fileExtension())) {
                continue;
            }
            try {
                for (WorkflowExtensions.ExtensionRef inner : WorkflowExtensions.scanDocument(ref,
                        jsonSerialization.deserialize(document.contentJson))) {
                    if (read.add(inner.resourceId().getId())) {
                        readTargetExtension(inner, result);
                    }
                }
            } catch (Exception e) {
                LOGGER.warnf(e, "Could not scan target parser %s for its dictionaries", ref.extensionUri());
            }
        }

        return result;
    }

    /**
     * Reads a typed extension config from the correct store, chosen by the
     * authority of the resource URI itself ({@code ai.labs.rules},
     * {@code ai.labs.llm}, …) rather than by the workflow step type
     * ({@code eddi://ai.labs.behavior}) — the two are different names for the same
     * thing, and matching on the wrong one resolved every extension to "unknown".
     * <p>
     * The {@code default} branch is unreachable: {@link WorkflowExtensions#scan}
     * only produces references for registered authorities. It throws rather than
     * returning null so that adding a type to the registry without adding it here
     * fails loudly.
     */
    private Object readTypedExtension(String authority, String id, int version) throws Exception {
        if (authority == null) {
            throw new IllegalStateException("No store authority for resource " + id);
        }
        return switch (authority) {
            case "ai.labs.parser" -> restInterfaceFactory.get(
                    IRestParserStore.class)
                    .readParser(id, version);
            case "ai.labs.dictionary" -> restInterfaceFactory.get(
                    IRestDictionaryStore.class)
                    .readRegularDictionary(id, version, "", "", 0, 0);
            case "ai.labs.rules" -> restInterfaceFactory.get(
                    IRestRuleSetStore.class)
                    .readRuleSet(id, version);
            case "ai.labs.apicalls" -> restInterfaceFactory.get(
                    IRestApiCallsStore.class)
                    .readApiCalls(id, version);
            case "ai.labs.llm" -> restInterfaceFactory.get(
                    IRestLlmStore.class)
                    .readLlm(id, version);
            case "ai.labs.property" -> restInterfaceFactory.get(
                    IRestPropertySetterStore.class)
                    .readPropertySetter(id, version);
            case "ai.labs.output" -> restInterfaceFactory.get(
                    IRestOutputStore.class)
                    .readOutputSet(id, version, "", "", 0, 0);
            case "ai.labs.mcpcalls" -> restInterfaceFactory.get(
                    IRestMcpCallsStore.class)
                    .readMcpCalls(id, version);
            case "ai.labs.rag" -> restInterfaceFactory.get(
                    IRestRagStore.class)
                    .readRag(id, version);
            default -> throw new IllegalStateException(
                    "No typed store read is registered for extension type " + authority);
        };
    }

    private String readTargetSnippetJson(String snippetId, int version) {
        try {
            PromptSnippet snippet = snippetStore.readSnippet(snippetId, version);
            return serializeSafe(snippet);
        } catch (Exception e) {
            LOGGER.debugf("Could not read target snippet %s v%d: %s", snippetId, version, e.getMessage());
            return null;
        }
    }

    /**
     * Builds a map of snippet name → resource ID by reading all snippet
     * descriptors. Uses the descriptor's name field directly (set during snippet
     * creation) to avoid the N+1 problem of loading each snippet individually.
     */
    private Map<String, IResourceId> buildExistingSnippetNameMap() {
        Map<String, IResourceId> nameMap = new LinkedHashMap<>();
        try {
            List<DocumentDescriptor> descriptors = snippetStore.readSnippetDescriptors("", 0, 0);
            for (DocumentDescriptor desc : descriptors) {
                try {
                    IResourceId resId = RestUtilities.extractResourceId(desc.getResource());
                    if (resId == null)
                        continue;
                    // Use descriptor name if available (avoids N+1 reads).
                    // Fall back to reading the snippet only when name is missing.
                    String name = desc.getName();
                    if (name == null || name.isBlank()) {
                        PromptSnippet snippet = snippetStore.readSnippet(resId.getId(), resId.getVersion());
                        name = snippet != null ? snippet.getName() : null;
                    }
                    if (name != null) {
                        nameMap.put(name, resId);
                    }
                } catch (Exception e) {
                    LOGGER.debugf("Could not read snippet for name map: %s", e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not build snippet name map: %s", e.getMessage());
        }
        return nameMap;
    }

    private String readDescriptorName(String resourceId) {
        try {
            DocumentDescriptor desc = documentDescriptorStore.readCurrentDescriptor(resourceId);
            return desc != null ? desc.getName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The version the target resource is actually at, or null when it cannot be
     * established.
     * <p>
     * Must go through {@link IDocumentDescriptorStore#readCurrentDescriptor}, which
     * resolves the current version first. The obvious-looking
     * {@code readDescriptor(resourceId, null)} cannot work: the descriptor store is
     * historized and its read does {@code checkNotNull(version)}, so a null version
     * <em>always</em> threw and the swallowed exception left every caller with
     * {@link #readLatestVersionOrDefault}'s fallback of 1. That is what made a sync
     * work exactly once per target: the second run still diffed against version 1,
     * showed the operator the pre-sync content as "target", and then wrote against
     * version 1 — which the store rejects once the first sync has moved the
     * resource to version 2 ("the store did not accept the update").
     */
    /**
     * The version the target agent is actually at.
     * <p>
     * The store is asked first and the descriptor only as a fallback, because the
     * store is the authority: the descriptor is a projection of it, and the two
     * disagree exactly when something went wrong — which is when getting this right
     * matters. Reading the store also means a descriptor that cannot be read costs
     * the version <em>number</em> shown beside the row, not the whole preview.
     */
    private Integer currentVersionOf(String agentId) {
        try {
            IResourceId current = agentStore.getCurrentResourceId(agentId);
            if (current != null && current.getVersion() != null) {
                return current.getVersion();
            }
        } catch (Exception e) {
            LOGGER.debugf("Store could not name the current version of %s: %s",
                    LogSanitizer.sanitize(agentId), LogSanitizer.sanitize(e.getMessage()));
        }
        return readLatestVersion(agentId);
    }

    /**
     * Whether the store has the agent at all. Only a definite "not found" answers
     * false; a store that cannot be asked answers true, so an outage stays a 5xx
     * rather than being reported as a missing agent.
     */
    private boolean targetAgentExists(String agentId) {
        try {
            IResourceId current = agentStore.getCurrentResourceId(agentId);
            return current != null && current.getId() != null;
        } catch (Exception e) {
            // instanceof for the same SneakyThrow reason as readTargetAgent.
            return !(e instanceof IResourceStore.ResourceNotFoundException || e instanceof NotFoundException);
        }
    }

    private Integer readLatestVersion(String resourceId) {
        try {
            DocumentDescriptor desc = documentDescriptorStore.readCurrentDescriptor(resourceId);
            if (desc != null && desc.getResource() != null) {
                IResourceId resId = RestUtilities.extractResourceId(desc.getResource());
                return resId != null ? resId.getVersion() : null;
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not establish the current version of %s: %s",
                    LogSanitizer.sanitize(resourceId), LogSanitizer.sanitize(e.getMessage()));
        }
        return null;
    }

    private int readLatestVersionOrDefault(String resourceId, int defaultVersion) {
        Integer version = readLatestVersion(resourceId);
        return version != null ? version : defaultVersion;
    }

    // ==================== Utilities ====================

    private String serializeSafe(Object obj) {
        if (obj == null)
            return null;
        try {
            return jsonSerialization.serialize(obj);
        } catch (Exception e) {
            LOGGER.debugf("Serialization failed: %s", e.getMessage());
            return null;
        }
    }

    /**
     * The source content as {@link UpgradeExecutor} would actually write it: with
     * the target's own values put back wherever the export's secret scrubber left a
     * placeholder.
     * <p>
     * Comparing the raw source against the target instead made every configuration
     * holding a credential — an LLM's apiKey, an httpCalls authorization header —
     * differ by the placeholder alone. Such a resource could never SKIP, so a sync
     * that changed nothing still wrote the resource, bumped its version, repointed
     * the workflow and bumped the agent version, for exactly the agents that matter
     * most.
     *
     * @return the source content, unchanged when it carries no placeholder or the
     *         target could not be read
     */
    private String secretNeutral(String sourceJson, String targetJson) {
        if (targetJson == null || !ScrubbedSecrets.carriesTargetBoundValue(sourceJson)) {
            return sourceJson;
        }
        try {
            String restored = ScrubbedSecrets.restore(sourceJson, targetJson, jsonSerialization);
            return restored != null ? restored : sourceJson;
        } catch (Exception e) {
            LOGGER.debugf("Could not neutralize scrubbed secrets before comparing: %s", e.getMessage());
            return sourceJson;
        }
    }

    /**
     * Agent JSON without the fields the target's store assigns itself, which say
     * nothing about the agent's content.
     * <p>
     * {@code compatibilityGeneration} is numbered per instance: the source's value
     * and the target's are unrelated, and the import writes neither. Compared as
     * content, it made an unchanged agent differ on every sync — and each such
     * "change" wrote a new agent version, which, being undeclared, is a breaking
     * one, so the agent's running conversations were cut off from every later
     * compatible version for nothing.
     *
     * @return the JSON without those fields, or the input unchanged when it cannot
     *         be parsed as an object
     */
    private String withoutStoreOwnedAgentFields(String agentJson) {
        if (agentJson == null) {
            return null;
        }
        try {
            if (jsonSerialization.deserialize(agentJson) instanceof Map<?, ?> map && map.containsKey(COMPATIBILITY_GENERATION_FIELD)) {
                Map<Object, Object> copy = new LinkedHashMap<>(map);
                copy.remove(COMPATIBILITY_GENERATION_FIELD);
                String stripped = jsonSerialization.serialize(copy);
                return stripped != null ? stripped : agentJson;
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not drop store-owned agent fields before comparing: %s", e.getMessage());
        }
        return agentJson;
    }

    private static final String COMPATIBILITY_GENERATION_FIELD = "compatibilityGeneration";

    /**
     * Compares two configs for equality of <em>content</em>, not of text.
     * <p>
     * The two sides are produced by different pipelines: source content is the
     * verbatim file text from a ZIP or the verbatim HTTP body from another
     * instance, while target content is a fresh serialization of a deserialized
     * object. Comparing them as strings made whitespace or field-ordering
     * differences look like changes, so SKIP effectively never fired and every
     * resource showed as modified.
     */
    private boolean contentEquals(String sourceJson, String targetJson) {
        if (Objects.equals(sourceJson, targetJson)) {
            return true;
        }
        if (sourceJson == null || targetJson == null) {
            return false;
        }
        return Objects.equals(canonicalJson(sourceJson), canonicalJson(targetJson));
    }

    /**
     * Re-serializes JSON with object keys sorted, so two renderings of the same
     * document compare equal. Returns the input unchanged when it cannot be parsed
     * — a comparison on raw text is still better than treating unparseable content
     * as equal.
     */
    private String canonicalJson(String json) {
        try {
            Object tree = jsonSerialization.deserialize(json);
            if (tree == null) {
                return json;
            }
            String canonical = jsonSerialization.serialize(sortKeys(tree));
            return canonical != null ? canonical : json;
        } catch (Exception e) {
            LOGGER.debugf("Could not canonicalize JSON for comparison: %s", e.getMessage());
            return json;
        }
    }

    private static Object sortKeys(Object node) {
        switch (node) {
            case Map<?, ?> map -> {
                Map<String, Object> sorted = new TreeMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    sorted.put(String.valueOf(entry.getKey()), sortKeys(entry.getValue()));
                }
                return sorted;
            }
            case List<?> list -> {
                List<Object> mapped = new ArrayList<>(list.size());
                for (Object element : list) {
                    mapped.add(sortKeys(element));
                }
                return mapped;
            }
            case null, default -> {
                return node;
            }
        }
    }
}
