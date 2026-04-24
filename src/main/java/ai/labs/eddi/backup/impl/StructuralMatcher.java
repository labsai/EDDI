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
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
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
 * <li><b>Extensions</b> — matched by {@code WorkflowStep.type} URI
 * (deterministic: each type appears at most once per workflow)</li>
 * <li><b>Snippets</b> — matched by {@code PromptSnippet.name} (natural
 * key)</li>
 * </ol>
 * <p>
 * This is the core matching engine shared by all import/sync flows. It is
 * stateless — all state comes from the {@link IResourceSource} and the target
 * agent's existing configuration.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class StructuralMatcher {

    private static final Logger log = Logger.getLogger(StructuralMatcher.class);

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
     *            if true, populate sourceContent/targetContent for diff view
     * @return the preview with all resource diffs
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
            try {
                targetConfig = readTargetAgent(targetAgentId);
                targetAgentName = readDescriptorName(targetAgentId);
            } catch (Exception e) {
                log.warnf("Could not load target agent %s: %s", targetAgentId, e.getMessage());
                // Proceed without target — all resources will be CREATE
                targetAgentId = null;
            }
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

        String sourceJson = includeContent ? serializeSafe(sourceAgent.config()) : null;
        String targetJson = includeContent ? serializeSafe(targetConfig) : null;

        DiffAction action = Objects.equals(sourceJson, targetJson)
                ? DiffAction.SKIP
                : DiffAction.UPDATE;

        Integer targetVersion = readLatestVersion(targetAgentId);

        return new ResourceDiff(
                sourceAgent.sourceId(), "agent", sourceAgent.name(),
                action, targetAgentId, targetVersion, "targetAgent",
                sourceJson, targetJson, -1);
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
        String sourceJson = includeContent ? serializeSafe(sourceWf.config()) : null;
        String targetJson = includeContent ? readTargetWorkflowJson(targetId, targetVersion) : null;
        DiffAction wfAction = Objects.equals(sourceJson, targetJson)
                ? DiffAction.SKIP
                : DiffAction.UPDATE;

        diffs.add(new ResourceDiff(
                sourceWf.sourceId(), "workflow",
                sourceWf.name() != null ? sourceWf.name() : targetName,
                wfAction, targetId, targetVersion, "position",
                sourceJson, targetJson, sourceWf.positionIndex()));

        // Extension diffs within this workflow — match by step type
        Map<String, ExtensionSourceData> sourceExtensions = sourceWf.extensions();
        Map<String, TargetExtension> targetExtensions = readTargetExtensions(targetId, targetVersion);

        for (Map.Entry<String, ExtensionSourceData> entry : sourceExtensions.entrySet()) {
            String stepType = entry.getKey();
            ExtensionSourceData sourceExt = entry.getValue();
            TargetExtension targetExt = targetExtensions.get(stepType);

            if (targetExt != null) {
                // Matched by type
                String srcContent = includeContent ? sourceExt.contentJson() : null;
                String tgtContent = includeContent ? targetExt.contentJson : null;
                DiffAction extAction = Objects.equals(srcContent, tgtContent)
                        ? DiffAction.SKIP
                        : DiffAction.UPDATE;

                diffs.add(new ResourceDiff(
                        sourceExt.sourceId(), sourceExt.type(), sourceExt.name(),
                        extAction, targetExt.id, targetExt.version, "type",
                        srcContent, tgtContent, -1));
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
            String sourceJson = includeContent ? serializeSafe(sourceSnippet.snippet()) : null;
            String targetJson = includeContent ? readTargetSnippetJson(existing.getId(), existing.getVersion()) : null;
            DiffAction action = Objects.equals(sourceJson, targetJson)
                    ? DiffAction.SKIP
                    : DiffAction.UPDATE;

            return new ResourceDiff(
                    sourceSnippet.sourceId(), "snippet", sourceSnippet.name(),
                    action, existing.getId(), existing.getVersion(), "name",
                    sourceJson, targetJson, -1);
        }

        return new ResourceDiff(
                sourceSnippet.sourceId(), "snippet", sourceSnippet.name(),
                DiffAction.CREATE, null, null, null,
                includeContent ? serializeSafe(sourceSnippet.snippet()) : null,
                null, -1);
    }

    // ==================== Target Reading Helpers ====================

    private AgentConfiguration readTargetAgent(String agentId) {
        try {
            int version = readLatestVersionOrDefault(agentId, 1);
            return agentStore.readAgent(agentId, version);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read target agent " + agentId, e);
        }
    }

    private String readTargetWorkflowJson(String workflowId, int version) {
        try {
            WorkflowConfiguration config = workflowStore.readWorkflow(workflowId, version);
            return serializeSafe(config);
        } catch (Exception e) {
            log.debugf("Could not read target workflow %s v%d: %s", workflowId, version, e.getMessage());
            return null;
        }
    }

    private record TargetExtension(String id, int version, String contentJson) {
    }

    /**
     * Reads all extensions from a target workflow by parsing its config and loading
     * each referenced resource via the correct typed store.
     */
    private Map<String, TargetExtension> readTargetExtensions(String workflowId, int version) {
        Map<String, TargetExtension> result = new LinkedHashMap<>();

        try {
            WorkflowConfiguration wfConfig = workflowStore.readWorkflow(workflowId, version);

            for (WorkflowConfiguration.WorkflowStep step : wfConfig.getWorkflowSteps()) {
                URI stepType = step.getType();
                if (stepType == null)
                    continue;

                Object uriObj = step.getExtensions().get("uri");
                if (uriObj == null)
                    continue;

                URI extUri = URI.create(uriObj.toString());
                IResourceId extResId = RestUtilities.extractResourceId(extUri);
                if (extResId == null)
                    continue;

                try {
                    Object extConfig = readTypedExtension(extUri, stepType.toString());
                    String json = serializeSafe(extConfig);
                    result.put(stepType.toString(), new TargetExtension(
                            extResId.getId(), extResId.getVersion(), json));
                } catch (Exception e) {
                    log.debugf("Could not read target extension %s: %s", extUri, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debugf("Could not read target workflow config %s v%d: %s", workflowId, version, e.getMessage());
        }

        return result;
    }

    /**
     * Reads a typed extension config from the correct store based on the step type.
     * This produces deterministic JSON serialization (matching what UpgradeExecutor
     * would write), unlike deserializing as {@code Object.class}.
     */
    private Object readTypedExtension(URI extUri, String stepType) throws Exception {
        IResourceId resId = RestUtilities.extractResourceId(extUri);
        if (resId == null)
            return null;

        return switch (stepType) {
            case "ai.labs.dictionary" -> restInterfaceFactory.get(
                    ai.labs.eddi.configs.dictionary.IRestDictionaryStore.class)
                    .readRegularDictionary(resId.getId(), resId.getVersion(), "", "", 0, 0);
            case "ai.labs.rules" -> restInterfaceFactory.get(
                    ai.labs.eddi.configs.rules.IRestRuleSetStore.class)
                    .readRuleSet(resId.getId(), resId.getVersion());
            case "ai.labs.apicalls" -> restInterfaceFactory.get(
                    ai.labs.eddi.configs.apicalls.IRestApiCallsStore.class)
                    .readApiCalls(resId.getId(), resId.getVersion());
            case "ai.labs.llm" -> restInterfaceFactory.get(
                    ai.labs.eddi.configs.llm.IRestLlmStore.class)
                    .readLlm(resId.getId(), resId.getVersion());
            case "ai.labs.property" -> restInterfaceFactory.get(
                    ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore.class)
                    .readPropertySetter(resId.getId(), resId.getVersion());
            case "ai.labs.output" -> restInterfaceFactory.get(
                    ai.labs.eddi.configs.output.IRestOutputStore.class)
                    .readOutputSet(resId.getId(), resId.getVersion(), "", "", 0, 0);
            case "ai.labs.mcpcalls" -> restInterfaceFactory.get(
                    ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore.class)
                    .readMcpCalls(resId.getId(), resId.getVersion());
            case "ai.labs.rag" -> restInterfaceFactory.get(
                    ai.labs.eddi.configs.rag.IRestRagStore.class)
                    .readRag(resId.getId(), resId.getVersion());
            default -> {
                log.debugf("Unknown step type for typed read: %s", stepType);
                yield null;
            }
        };
    }

    private String readTargetSnippetJson(String snippetId, int version) {
        try {
            PromptSnippet snippet = snippetStore.readSnippet(snippetId, version);
            return serializeSafe(snippet);
        } catch (Exception e) {
            log.debugf("Could not read target snippet %s v%d: %s", snippetId, version, e.getMessage());
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
                    log.debugf("Could not read snippet for name map: %s", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debugf("Could not build snippet name map: %s", e.getMessage());
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
            log.debugf("Serialization failed: %s", e.getMessage());
            return null;
        }
    }
}
