/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.ISharedArtifactStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ArtifactConfig;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.modules.llm.tools.impl.ArtifactTools;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceProvider;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Contributes the shared-artifact tools (I17) — {@code createArtifact},
 * {@code readArtifact}, {@code proposeArtifactUpdate}, {@code listArtifacts} —
 * when, and only when, the turn belongs to a live group discussion whose config
 * sets {@code artifactConfig.allowArtifactTools}.
 * <p>
 * Gate discipline is identical to {@link GroupTaskToolsProvider}, and for the
 * same reasons: membership (not existence) via
 * {@code LiveDiscussionRegistry#getForMember} because the group conversation id
 * is a caller-supplied context variable; the member agent's own
 * {@code enableBuiltInTools} switch still applies; and every uncertainty —
 * missing config, unreadable group, absent store — resolves to "contribute
 * nothing" (fail-closed, gate by absence).
 *
 * @author ginccc
 */
class ArtifactToolsProvider implements ToolSourceProvider {

    private static final Logger LOGGER = Logger.getLogger(ArtifactToolsProvider.class);

    private final LiveDiscussionRegistry liveDiscussionRegistry;
    private final IAgentGroupStore groupStore;
    private final ISharedArtifactStore artifactStore;

    ArtifactToolsProvider(LiveDiscussionRegistry liveDiscussionRegistry, IAgentGroupStore groupStore,
            ISharedArtifactStore artifactStore) {
        this.liveDiscussionRegistry = liveDiscussionRegistry;
        this.groupStore = groupStore;
        this.artifactStore = artifactStore;
    }

    @Override
    public String source() {
        return "builtin";
    }

    @Override
    public ToolContribution contribute(ToolAssemblyContext ctx) {
        String groupConversationId = ctx.groupConversationId();
        if (groupConversationId == null || liveDiscussionRegistry == null || groupStore == null || artifactStore == null) {
            return ToolContribution.empty();
        }
        Boolean enableBuiltInTools = ctx.task() != null ? ctx.task().getEnableBuiltInTools() : null;
        if (enableBuiltInTools == null || !enableBuiltInTools) {
            return ToolContribution.empty();
        }
        String callerConversationId = ctx.memory() != null ? ctx.memory().getConversationId() : null;
        var live = liveDiscussionRegistry.getForMember(groupConversationId, callerConversationId);
        if (live.isEmpty()) {
            return ToolContribution.empty();
        }
        AgentGroupConfiguration groupConfiguration = resolveGroup(live.get().getGroupId());
        ArtifactConfig config = groupConfiguration != null ? groupConfiguration.getArtifactConfig() : null;
        if (config == null || !config.allowArtifactTools()) {
            return ToolContribution.empty();
        }

        var tools = List.<Object>of(new ArtifactTools(liveDiscussionRegistry, groupConversationId, config, ctx.agentId(),
                artifactStore));
        var reflected = ToolObjectReflector.reflect(tools);
        return new ToolContribution(reflected.specs(), reflected.executors(), reflected.toolSources(), Map.of(),
                List.of(), reflected.toolCanonicalNames());
    }

    /**
     * The group config, or {@code null} if it cannot be read — a store failure
     * withholds the write tools (fail-closed), logged so the operator sees why they
     * vanished.
     */
    private AgentGroupConfiguration resolveGroup(String groupId) {
        if (groupId == null) {
            return null;
        }
        try {
            var resourceId = groupStore.getCurrentResourceId(groupId);
            if (resourceId == null) {
                return null;
            }
            return groupStore.read(groupId, resourceId.getVersion());
        } catch (Exception e) {
            LOGGER.warnf("Could not read artifact policy for group '%s' — withholding the artifact tools: %s", groupId, e.getMessage());
            return null;
        }
    }
}
