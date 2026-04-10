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
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.runtime.client.configuration.ResourceClientLibrary;
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
    private final ResourceClientLibrary resourceClientLibrary;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public StructuralMatcher(IRestAgentStore agentStore,
            IDocumentDescriptorStore documentDescriptorStore,
            IRestPromptSnippetStore snippetStore,
            ResourceClientLibrary resourceClientLibrary,
            IJsonSerialization jsonSerialization) {
        this.agentStore = agentStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.snippetStore = snippetStore;
        this.resourceClientLibrary = resourceClientLibrary;
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

        DiffAction action = (sourceJson != null && sourceJson.equals(targetJson))
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
        DiffAction wfAction = (sourceJson != null && sourceJson.equals(targetJson))
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
                DiffAction extAction = contentEquals(srcContent, tgtContent)
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
            DiffAction action = contentEquals(sourceJson, targetJson)
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
            // Read the latest version
            DocumentDescriptor descriptor = documentDescriptorStore.readDescriptor(agentId, null);
            int version = descriptor != null && descriptor.getResource() != null
                    ? RestUtilities.extractResourceId(descriptor.getResource()).getVersion()
                    : 1;
            return agentStore.readAgent(agentId, version);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read target agent " + agentId, e);
        }
    }

    private String readTargetWorkflowJson(String workflowId, int version) {
        try {
            Object config = resourceClientLibrary.getResource(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=" + version),
                    WorkflowConfiguration.class);
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
     * each referenced resource.
     */
    private Map<String, TargetExtension> readTargetExtensions(String workflowId, int version) {
        Map<String, TargetExtension> result = new LinkedHashMap<>();

        try {
            WorkflowConfiguration wfConfig = resourceClientLibrary.getResource(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=" + version),
                    WorkflowConfiguration.class);

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
                    Object extConfig = resourceClientLibrary.getResource(extUri, Object.class);
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

    private String readTargetSnippetJson(String snippetId, int version) {
        try {
            PromptSnippet snippet = snippetStore.readSnippet(snippetId, version);
            return serializeSafe(snippet);
        } catch (Exception e) {
            log.debugf("Could not read target snippet %s v%d: %s", snippetId, version, e.getMessage());
            return null;
        }
    }

    private Map<String, IResourceId> buildExistingSnippetNameMap() {
        Map<String, IResourceId> nameMap = new LinkedHashMap<>();
        try {
            List<DocumentDescriptor> descriptors = snippetStore.readSnippetDescriptors("", 0, 0);
            for (DocumentDescriptor desc : descriptors) {
                try {
                    IResourceId resId = RestUtilities.extractResourceId(desc.getResource());
                    if (resId == null)
                        continue;
                    PromptSnippet snippet = snippetStore.readSnippet(resId.getId(), resId.getVersion());
                    if (snippet != null && snippet.getName() != null) {
                        nameMap.put(snippet.getName(), resId);
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

    // ==================== Utilities ====================

    private boolean contentEquals(String a, String b) {
        if (a == null || b == null)
            return false;
        return a.equals(b);
    }

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
