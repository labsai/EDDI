/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupTaskConfig;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.modules.llm.tools.impl.GroupTaskTools;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceProvider;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Contributes {@code addGroupTask} / {@code listGroupTasks} when — and only
 * when — the turn belongs to a group discussion whose config asks for them
 * (I5).
 * <p>
 * <b>There is no permissive standalone default.</b> Every other gate this
 * provider could have used degrades open: a missing config could mean "allow",
 * an absent group id could mean "not in a group, so unrestricted". Both
 * readings would hand an arbitrary agent a write tool into a task list, so both
 * are refused here. The tool is assembled only on the positive case — a group
 * conversation id in the assembly context, a resolvable group config, and
 * {@code allowAgentTaskCreation} explicitly true.
 * <p>
 * Gating by <em>absence</em> rather than by runtime rejection is deliberate: a
 * tool that is not assembled costs no prompt tokens, cannot be described to the
 * model, and cannot be argued with. A tool that exists and always says no
 * invites the model to keep trying.
 *
 * @author ginccc
 */
class GroupTaskToolsProvider implements ToolSourceProvider {

    private static final Logger LOGGER = Logger.getLogger(GroupTaskToolsProvider.class);

    private final LiveDiscussionRegistry liveDiscussionRegistry;
    private final IAgentGroupStore groupStore;

    GroupTaskToolsProvider(LiveDiscussionRegistry liveDiscussionRegistry, IAgentGroupStore groupStore) {
        this.liveDiscussionRegistry = liveDiscussionRegistry;
        this.groupStore = groupStore;
    }

    @Override
    public String source() {
        return "builtin";
    }

    @Override
    public ToolContribution contribute(ToolAssemblyContext ctx) {
        String groupConversationId = ctx.groupConversationId();
        if (groupConversationId == null || liveDiscussionRegistry == null || groupStore == null) {
            return ToolContribution.empty();
        }
        var live = liveDiscussionRegistry.get(groupConversationId);
        if (live.isEmpty()) {
            return ToolContribution.empty();
        }
        AgentGroupConfiguration groupConfiguration = resolveGroup(live.get().getGroupId());
        GroupTaskConfig config = groupConfiguration != null ? groupConfiguration.getTaskListConfig() : null;
        if (config == null || !config.allowAgentTaskCreation()) {
            return ToolContribution.empty();
        }

        var tools = List.<Object>of(new GroupTaskTools(liveDiscussionRegistry, groupConversationId, config, ctx.agentId(),
                groupConfiguration.getMembers(), groupConfiguration.getModeratorAgentId()));
        var reflected = ToolObjectReflector.reflect(tools);
        return new ToolContribution(reflected.specs(), reflected.executors(), reflected.toolSources(), Map.of(),
                List.of(), reflected.toolCanonicalNames());
    }

    /**
     * The group config, or {@code null} if it cannot be read.
     * <p>
     * A store failure returns {@code null} — "could not read the policy" is not
     * "there is no policy", and the only safe reading of an unknown policy for a
     * write tool is to withhold it. The SPI's isolation wrapper would swallow a
     * throw here into an empty contribution anyway; catching it explicitly lets the
     * operator see why the tools vanished.
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
            LOGGER.warnf("Could not read task-list policy for group '%s' — withholding the task tools: %s", groupId, e.getMessage());
            return null;
        }
    }
}
