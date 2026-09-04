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
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
     *             if {@code targetAgentId} was given but could not be read
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
            // target that cannot be read is a 404 — not a silent switch to "create
            // everything", which previewed an upgrade as a full duplicate of the
            // agent and gave the operator nothing to distinguish the two.
            targetConfig = readTargetAgent(targetAgentId);
            targetAgentName = readDescriptorName(targetAgentId);
        }

        // 1. Agent-level diff
        diffs.add(buildAgentDiff(sourceAgent, targetAgentId, targetConfig, includeContent));

        // 2. Workflow diffs (matched by position)
        List<URI> targetWorkflowUris = targetConfig != null ? targetConfig.getWorkflows() : List.of();
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
                diffs);
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

        String sourceJson = serializeSafe(sourceAgent.config());
        String targetJson = serializeSafe(targetConfig);

        DiffAction action = contentEquals(sourceJson, targetJson)
                ? DiffAction.SKIP
                : DiffAction.UPDATE;

        Integer targetVersion = readLatestVersion(targetAgentId);

        return new ResourceDiff(
                sourceAgent.sourceId(), "agent", sourceAgent.name(),
                action, targetAgentId, targetVersion, "targetAgent",
                includeContent ? sourceJson : null,
                includeContent ? targetJson : null, -1);
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

        // Workflow-level diff
        String sourceJson = serializeSafe(sourceWf.config());
        String targetJson = readTargetWorkflowJson(targetId, targetVersion);
        DiffAction wfAction = contentEquals(sourceJson, targetJson)
                ? DiffAction.SKIP
                : DiffAction.UPDATE;

        diffs.add(new ResourceDiff(
                sourceWf.sourceId(), "workflow",
                sourceWf.name() != null ? sourceWf.name() : targetName,
                wfAction, targetId, targetVersion, "position",
                includeContent ? sourceJson : null,
                includeContent ? targetJson : null, sourceWf.positionIndex()));

        // Extension diffs within this workflow — matched by the canonical
        // WorkflowExtensions key, which both sides derive the same way.
        Map<String, ExtensionSourceData> sourceExtensions = sourceWf.extensions();
        Map<String, TargetExtension> targetExtensions = readTargetExtensions(targetId, targetVersion);

        for (Map.Entry<String, ExtensionSourceData> entry : sourceExtensions.entrySet()) {
            String extensionKey = entry.getKey();
            ExtensionSourceData sourceExt = entry.getValue();
            TargetExtension targetExt = targetExtensions.get(extensionKey);

            if (targetExt != null) {
                // Matched by step type + occurrence
                String srcContent = sourceExt.contentJson();
                String tgtContent = targetExt.contentJson;
                DiffAction extAction = contentEquals(srcContent, tgtContent)
                        ? DiffAction.SKIP
                        : DiffAction.UPDATE;

                diffs.add(new ResourceDiff(
                        sourceExt.sourceId(), sourceExt.type(), sourceExt.name(),
                        extAction, targetExt.id, targetExt.version, "type",
                        includeContent ? srcContent : null,
                        includeContent ? tgtContent : null, -1));
            } else {
                // No match — new extension type in this workflow
                diffs.add(new ResourceDiff(
                        sourceExt.sourceId(), sourceExt.type(), sourceExt.name(),
                        DiffAction.CREATE, null, null, null,
                        includeContent ? sourceExt.contentJson() : null,
                        null, -1));
            }
        }

        return diffs;
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

    // ==================== Snippet Diffs ====================

    private ResourceDiff buildSnippetDiff(SnippetSourceData sourceSnippet,
                                          Map<String, IResourceId> existingByName,
                                          boolean includeContent) {
        IResourceId existing = existingByName.get(sourceSnippet.name());

        if (existing != null) {
            String sourceJson = serializeSafe(sourceSnippet.snippet());
            String targetJson = readTargetSnippetJson(existing.getId(), existing.getVersion());
            DiffAction action = contentEquals(sourceJson, targetJson)
                    ? DiffAction.SKIP
                    : DiffAction.UPDATE;

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

    private AgentConfiguration readTargetAgent(String agentId) {
        AgentConfiguration config;
        try {
            int version = readLatestVersionOrDefault(agentId, 1);
            config = agentStore.readAgent(agentId, version);
        } catch (NotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new NotFoundException("Could not read target agent " + agentId + ": " + e.getMessage(), e);
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

    private record TargetExtension(String id, int version, String contentJson) {
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

        for (WorkflowExtensions.ExtensionRef ref : WorkflowExtensions.scan(wfConfig)) {
            try {
                Object extConfig = readTypedExtension(ref);
                String json = serializeSafe(extConfig);
                result.put(ref.key(), new TargetExtension(
                        ref.resourceId().getId(), ref.resourceId().getVersion(), json));
            } catch (Exception e) {
                LOGGER.warnf(e, "Could not read target extension %s", ref.extensionUri());
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
    private Object readTypedExtension(WorkflowExtensions.ExtensionRef ref) throws Exception {
        IResourceId resId = ref.resourceId();

        return switch (ref.type().resourceAuthority()) {
            case "ai.labs.dictionary" -> restInterfaceFactory.get(
                    IRestDictionaryStore.class)
                    .readRegularDictionary(resId.getId(), resId.getVersion(), "", "", 0, 0);
            case "ai.labs.rules" -> restInterfaceFactory.get(
                    IRestRuleSetStore.class)
                    .readRuleSet(resId.getId(), resId.getVersion());
            case "ai.labs.apicalls" -> restInterfaceFactory.get(
                    IRestApiCallsStore.class)
                    .readApiCalls(resId.getId(), resId.getVersion());
            case "ai.labs.llm" -> restInterfaceFactory.get(
                    IRestLlmStore.class)
                    .readLlm(resId.getId(), resId.getVersion());
            case "ai.labs.property" -> restInterfaceFactory.get(
                    IRestPropertySetterStore.class)
                    .readPropertySetter(resId.getId(), resId.getVersion());
            case "ai.labs.output" -> restInterfaceFactory.get(
                    IRestOutputStore.class)
                    .readOutputSet(resId.getId(), resId.getVersion(), "", "", 0, 0);
            case "ai.labs.mcpcalls" -> restInterfaceFactory.get(
                    IRestMcpCallsStore.class)
                    .readMcpCalls(resId.getId(), resId.getVersion());
            case "ai.labs.rag" -> restInterfaceFactory.get(
                    IRestRagStore.class)
                    .readRag(resId.getId(), resId.getVersion());
            default -> throw new IllegalStateException(
                    "No typed store read is registered for extension type " + ref.type().resourceAuthority());
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
            DocumentDescriptor desc = documentDescriptorStore.readDescriptor(resourceId, null);
            return desc != null ? desc.getName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Integer readLatestVersion(String resourceId) {
        try {
            DocumentDescriptor desc = documentDescriptorStore.readDescriptor(resourceId, null);
            if (desc != null && desc.getResource() != null) {
                IResourceId resId = RestUtilities.extractResourceId(desc.getResource());
                return resId != null ? resId.getVersion() : null;
            }
        } catch (Exception e) {
            // ignore
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
