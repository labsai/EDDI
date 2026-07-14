/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.groups.IRestAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TaskDefinition;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.utils.LogSanitizer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.engine.mcp.McpToolUtils.*;

/**
 * MCP tools for managing agent groups and group conversations. Exposes CRUD for
 * group configurations and discussion orchestration as MCP-compliant tools.
 * <p>
 * Phase 10 — Group Conversations
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpGroupTools {

    private static final Logger LOGGER = Logger.getLogger(McpGroupTools.class);

    private final IRestAgentGroupStore groupStore;
    private final IGroupConversationService groupConversationService;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final OwnershipValidator ownershipValidator;
    private final boolean authEnabled;

    @Inject
    public McpGroupTools(IRestAgentGroupStore groupStore, IGroupConversationService groupConversationService, IJsonSerialization jsonSerialization,
            SecurityIdentity identity, OwnershipValidator ownershipValidator,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.groupStore = groupStore;
        this.groupConversationService = groupConversationService;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.ownershipValidator = ownershipValidator;
        this.authEnabled = authEnabled;
    }

    /**
     * MCP parity with the REST surface: a specific group conversation may only be
     * read or mutated by its owner (or an admin). The MCP role check alone is a
     * coarse gate — without this, any caller holding the baseline MCP role could
     * read, append to, re-run or close ANOTHER user's group conversation, while the
     * equivalent REST endpoints all enforce {@code requireOwnerOrAdmin} (403).
     */
    private void requireConversationOwner(String groupConversationId)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        GroupConversation gc = groupConversationService.readGroupConversation(groupConversationId);
        ownershipValidator.requireOwnerOrAdmin(identity, gc.getUserId(), "group conversation");
    }

    /**
     * Uniform, non-leaking denial for an MCP call on someone else's conversation.
     */
    private String accessDenied(String tool, String groupConversationId) {
        LOGGER.infof("%s denied: caller does not own group conversation %s",
                tool, LogSanitizer.sanitize(groupConversationId));
        return errorJson("Access denied: you do not own this group conversation");
    }

    // --- Discovery ---

    @Tool(description = "Describe all available discussion styles for agent " + "groups. Returns the name, phase flow, and recommended use case "
            + "for each style (ROUND_TABLE, PEER_REVIEW, DEVIL_ADVOCATE, " + "DELPHI, DEBATE, TASK_FORCE). Call this before create_group to "
            + "choose the right style.")
    public String describe_discussion_styles() {
        requireRole(identity, authEnabled, "eddi-viewer");
        return """
                ## Discussion Styles

                ### ROUND_TABLE (default)
                Flow: Opinion → Discussion (N rounds) → Synthesis
                Use when: Brainstorming, open-ended exploration, general Q&A panels.
                Member roles: none required.

                ### PEER_REVIEW
                Flow: Opinion → Critique (each agent reviews each peer) → Revision → Synthesis
                Use when: Code review, document review, proposal evaluation — anywhere structured feedback matters.
                Member roles: none required. All members critique all others.

                ### DEVIL_ADVOCATE
                Flow: Opinion → Challenge (devil argues against consensus) → Defense → Synthesis
                Use when: Risk assessment, stress-testing assumptions, identifying blind spots.
                Member roles: assign one member role=DEVIL_ADVOCATE.

                ### DELPHI
                Flow: Independent Opinion → Anonymous Sharing → Revised Opinion → ... → Synthesis
                Use when: Forecasting, reducing groupthink, getting unbiased independent estimates.
                Member roles: none required. Opinions are anonymized between rounds.

                ### DEBATE
                Flow: Pro Opening → Con Opening → Pro Rebuttal → Con Rebuttal → Judge
                Use when: Evaluating trade-offs, pro/con analysis, technology comparisons.
                Member roles: assign members role=PRO or role=CON. Moderator acts as judge.

                ### TASK_FORCE
                Flow: Plan → Execute (parallel) → Verify → Synthesis
                TASK_FORCE — Collaborative task accomplishment. The moderator decomposes the
                goal into tasks, each agent executes their assigned tasks in parallel, a
                verification phase checks results, and a synthesis phase combines everything.
                Best for: concrete deliverables, divide-and-conquer goals, project-style work.
                Member roles: none required. Moderator handles planning and synthesis.
                Optional: pass pre-configured tasks via the `tasks` parameter to skip the
                PLAN phase entirely.

                ## Nested Groups (Group-of-Groups)
                Members can be other groups (memberTypes=GROUP). The sub-group runs its
                own full discussion and its synthesized answer becomes the member's response.
                Use this for parallel review panels, red-team vs blue-team, or tournament brackets.

                ## Parameters
                - maxRounds: controls repeat count for ROUND_TABLE and DELPHI (default 2)
                - moderatorAgentId: required for synthesis/judging phase
                - memberRoles: comma-separated roles matching member positions
                - memberTypes: comma-separated types: AGENT (default) or GROUP
                - tasks: (TASK_FORCE only) JSON array of pre-configured task definitions.
                  Each task: {"subject":"...","description":"...","assignToRole":"ALL",
                  "dependsOn":[],"priority":0}. If provided, the PLAN phase is skipped.
                """;
    }

    // --- Group Config CRUD ---

    @Tool(description = "List all agent group configurations. Returns " + "descriptors with name, ID, and last modified date.")
    public String list_groups(@ToolArg(description = "Filter by group name (optional)") String filter,
                              @ToolArg(description = "Page index, 0-based (default 0)") String index,
                              @ToolArg(description = "Page size (default 20)") String limit) {
        requireRole(identity, authEnabled, "eddi-editor");
        try {
            int idx = parseIntOrDefault(index, 0);
            int lim = parseIntOrDefault(limit, 20);
            String flt = filter != null ? filter : "";
            List<DocumentDescriptor> descriptors = groupStore.readGroupDescriptors(flt, idx, lim);
            return jsonSerialization.serialize(descriptors);
        } catch (Exception e) {
            LOGGER.errorf("list_groups failed: %s", e.getMessage());
            return errorJson(e.getMessage());
        }
    }

    @Tool(description = "Read a group configuration including its members, " + "discussion style, phases, and protocol settings.")
    public String read_group(@ToolArg(description = "Group configuration ID") String groupId,
                             @ToolArg(description = "Version number (0 or omit for latest)") String version) {
        requireRole(identity, authEnabled, "eddi-editor");
        try {
            int ver = parseIntOrDefault(version, 0);
            if (ver == 0) {
                ver = groupStore.getCurrentVersion(groupId);
            }
            AgentGroupConfiguration config = groupStore.readGroup(groupId, ver);
            return jsonSerialization.serialize(config);
        } catch (Exception e) {
            LOGGER.errorf("read_group failed: %s", e.getMessage());
            return errorJson(e.getMessage());
        }
    }

    @Tool(description = "Create a new agent group for multi-agent discussions. " + "Call describe_discussion_styles first to choose a style. "
            + "Members can be agents (default) or nested groups (memberTypes=GROUP).")
    public String create_group(@ToolArg(description = "Group name") String name,
                               @ToolArg(description = "Group description (optional)") String description,
                               @ToolArg(description = "Comma-separated member IDs (agent IDs or "
                                       + "group IDs depending on memberTypes)") String memberAgentIds,
                               @ToolArg(description = "Comma-separated display names (optional)") String memberDisplayNames,
                               @ToolArg(description = "Comma-separated member roles: PARTICIPANT, "
                                       + "DEVIL_ADVOCATE, PRO, CON (optional)") String memberRoles,
                               @ToolArg(description = "Comma-separated member types: AGENT "
                                       + "(default) or GROUP for nested groups (optional)") String memberTypes,
                               @ToolArg(description = "Moderator agent ID (optional)") String moderatorAgentId,
                               @ToolArg(description = "Discussion style: ROUND_TABLE, PEER_REVIEW, "
                                       + "DEVIL_ADVOCATE, DELPHI, DEBATE, TASK_FORCE (default ROUND_TABLE)") String style,
                               @ToolArg(description = "Max rounds (default 2)") String maxRounds,
                               @ToolArg(description = "Maximum total agent turns across all phases (default 50). "
                                       + "Safety cap to prevent runaway discussions.") String maxTurns,
                               @ToolArg(description = "JSON array of pre-configured tasks for TASK_FORCE style "
                                       + "(optional). Each element: {\"subject\":\"...\",\"description\":\"...\","
                                       + "\"assignToRole\":\"ALL\",\"dependsOn\":[],\"priority\":0}. "
                                       + "If provided, the PLAN phase is skipped.") String tasks) {
        requireRole(identity, authEnabled, "eddi-editor");
        try {
            AgentGroupConfiguration config = new AgentGroupConfiguration();
            config.setName(name);
            config.setDescription(description);

            // Parse members
            String[] agentIds = memberAgentIds.split(",");
            String[] displayNames = memberDisplayNames != null ? memberDisplayNames.split(",") : new String[0];
            String[] roles = memberRoles != null ? memberRoles.split(",") : new String[0];
            String[] types = memberTypes != null ? memberTypes.split(",") : new String[0];

            List<GroupMember> members = new ArrayList<>();
            for (int i = 0; i < agentIds.length; i++) {
                String displayName = i < displayNames.length ? displayNames[i].trim() : "Agent " + (i + 1);
                String role = i < roles.length && !roles[i].trim().isBlank() ? roles[i].trim().toUpperCase() : null;
                if ("PARTICIPANT".equals(role)) {
                    role = null;
                }
                var type = i < types.length && "GROUP".equalsIgnoreCase(types[i].trim())
                        ? AgentGroupConfiguration.MemberType.GROUP
                        : AgentGroupConfiguration.MemberType.AGENT;
                members.add(new GroupMember(agentIds[i].trim(), displayName, i + 1, role, type));
            }
            config.setMembers(members);

            if (moderatorAgentId != null && !moderatorAgentId.isBlank()) {
                config.setModeratorAgentId(moderatorAgentId.trim());
            }

            // Style
            DiscussionStyle discussionStyle = DiscussionStyle.ROUND_TABLE;
            if (style != null && !style.isBlank()) {
                try {
                    discussionStyle = DiscussionStyle.valueOf(style.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    // Fall back to ROUND_TABLE
                }
            }
            config.setStyle(discussionStyle);
            config.setMaxRounds(parseIntOrDefault(maxRounds, 2));

            // Pre-configured tasks (TASK_FORCE style — skips PLAN phase)
            if (tasks != null && !tasks.isBlank()) {
                try {
                    TaskDefinition[] taskArray = jsonSerialization.deserialize(tasks, TaskDefinition[].class);
                    config.setTasks(List.of(taskArray));
                } catch (Exception ex) {
                    return errorJson("Invalid tasks JSON: " + ex.getMessage());
                }
            }

            // Protocol with maxTurns safety cap
            int mt = parseIntOrDefault(maxTurns, 0);
            config.setProtocol(new ProtocolConfig(60, ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                    ProtocolConfig.MemberUnavailablePolicy.SKIP, mt));

            Response response = groupStore.createGroup(config);
            String location = response.getLocation() != null ? response.getLocation().toString() : "";
            String groupId = extractIdFromLocation(location);

            // Build a rich response for AI clients
            var phases = DiscussionStylePresets.expand(discussionStyle, config.getMaxRounds());
            var phaseNames = phases.stream().map(p -> p.name()).toList();

            return ("Created group '%s' (style=%s, %d members, " + "moderator=%s)\nID: %s\nPhases: %s").formatted(name, discussionStyle,
                    members.size(), moderatorAgentId != null ? moderatorAgentId : "none", groupId, String.join(" → ", phaseNames));
        } catch (Exception e) {
            LOGGER.errorf("create_group failed: %s", e.getMessage());
            return errorJson(e.getMessage());
        }
    }

    @Tool(description = "Update an existing agent group. Pass the full " + "configuration as JSON.")
    public String update_group(@ToolArg(description = "Group ID") String groupId,
                               @ToolArg(description = "Version number (0 for latest)") String version,
                               @ToolArg(description = "Full JSON configuration body") String configJson) {
        requireRole(identity, authEnabled, "eddi-editor");
        try {
            int ver = parseIntOrDefault(version, 0);
            AgentGroupConfiguration config = jsonSerialization.deserialize(configJson, AgentGroupConfiguration.class);
            groupStore.updateGroup(groupId, ver, config);
            return "Updated group " + groupId;
        } catch (Exception e) {
            LOGGER.errorf("update_group failed: %s", e.getMessage());
            return errorJson(e.getMessage());
        }
    }

    @Tool(description = "Delete an agent group configuration")
    public String delete_group(@ToolArg(description = "Group ID") String groupId,
                               @ToolArg(description = "Version number (0 for latest)") String version) {
        requireRole(identity, authEnabled, "eddi-editor");
        try {
            int ver = parseIntOrDefault(version, 0);
            groupStore.deleteGroup(groupId, ver, false);
            return "Deleted group " + groupId;
        } catch (Exception e) {
            LOGGER.errorf("delete_group failed: %s", e.getMessage());
            return errorJson(e.getMessage());
        }
    }

    // --- Group Conversation ---

    @Blocking
    @Tool(description = "Start a structured multi-agent discussion and wait for it to complete. "
            + "All configured member agents participate using the group's discussion style. "
            + "Returns the full GroupConversation including transcript, task list (for TASK_FORCE), "
            + "dynamic agent tracking, and synthesized answer. "
            + "WARNING: TASK_FORCE discussions with many agents/tasks can take several minutes. "
            + "For long-running discussions, use start_group_discussion instead (returns immediately, "
            + "poll with read_group_conversation).")
    public String discuss_with_group(@ToolArg(description = "Group configuration ID (from create_group " + "or list_groups)") String groupId,
                                     @ToolArg(description = "The question or topic for the group to " + "discuss") String question,
                                     @ToolArg(description = "User ID (optional, defaults to " + "'mcp-client')") String userId) {
        requireRole(identity, authEnabled, "eddi-viewer");
        try {
            String user = userId != null && !userId.isBlank() ? userId : "mcp-client";
            GroupConversation gc = groupConversationService.discuss(groupId, question, user, 0);
            return jsonSerialization.serialize(gc);
        } catch (Exception e) {
            LOGGER.errorf("discuss_with_group failed: %s", e.getMessage());
            return errorJson(e.getMessage());
        }
    }

    @Tool(description = "Read a group conversation including its full transcript, task list "
            + "(for TASK_FORCE discussions with per-task status, assignments, and results), "
            + "dynamic agent tracking (createdAgentIds, retainedAgentIds), synthesized answer, "
            + "and conversation state. Use this to poll for completion after start_group_discussion, "
            + "or to inspect task-level results after a TASK_FORCE discussion.")
    public String read_group_conversation(
                                          @ToolArg(description = "Group conversation ID (from "
                                                  + "discuss_with_group, start_group_discussion, "
                                                  + "or list_group_conversations)") String groupConversationId) {
        requireRole(identity, authEnabled, "eddi-viewer");
        try {
            GroupConversation gc = groupConversationService.readGroupConversation(groupConversationId);
            ownershipValidator.requireOwnerOrAdmin(identity, gc.getUserId(), "group conversation");
            return jsonSerialization.serialize(gc);
        } catch (ForbiddenException e) {
            return accessDenied("read_group_conversation", groupConversationId);
        } catch (Exception e) {
            LOGGER.errorf("read_group_conversation failed: %s", e.getMessage());
            return errorJson(e.getMessage());
        }
    }

    @Tool(description = "List past group conversation transcripts for a " + "group. Returns conversation IDs, state, question, and " + "timestamps.")
    public String list_group_conversations(@ToolArg(description = "Group configuration ID") String groupId,
                                           @ToolArg(description = "Page index, 0-based (default 0)") String index,
                                           @ToolArg(description = "Page size (default 20)") String limit) {
        requireRole(identity, authEnabled, "eddi-viewer");
        try {
            int idx = parseIntOrDefault(index, 0);
            int lim = parseIntOrDefault(limit, 20);
            List<GroupConversation> conversations = groupConversationService.listGroupConversations(groupId, idx, lim);
            return jsonSerialization.serialize(conversations);
        } catch (Exception e) {
            LOGGER.errorf("list_group_conversations failed: %s", e.getMessage());
            return errorJson(e.getMessage());
        }
    }

    // --- Async Discussion + Delete ---

    @Tool(description = "Start a group discussion asynchronously and return immediately "
            + "with the conversation ID and IN_PROGRESS state. Use this instead of "
            + "discuss_with_group for TASK_FORCE or other long-running discussions. "
            + "Poll with read_group_conversation to check progress and get results "
            + "when state changes to COMPLETED or FAILED.")
    public String start_group_discussion(
                                         @ToolArg(description = "Group configuration ID (from create_group or list_groups)") String groupId,
                                         @ToolArg(description = "The question or topic for the group to discuss") String question,
                                         @ToolArg(description = "User ID (optional, defaults to 'mcp-client')") String userId) {
        requireRole(identity, authEnabled, "eddi-viewer");
        try {
            String user = userId != null && !userId.isBlank() ? userId : "mcp-client";
            GroupConversation gc = groupConversationService.startAndDiscussAsync(groupId, question, user, null);
            return jsonSerialization.serialize(java.util.Map.of(
                    "groupConversationId", gc.getId(),
                    "state", String.valueOf(gc.getState()),
                    "message", "Discussion started. Poll read_group_conversation with this ID to check progress."));
        } catch (Exception e) {
            LOGGER.errorf("start_group_discussion failed: %s", e.getMessage());
            return errorJson(e.getMessage());
        }
    }

    @Tool(description = "Delete a group conversation and cascade-delete all member "
            + "conversations created during the discussion.")
    public String delete_group_conversation(
                                            @ToolArg(description = "Group conversation ID to delete") String groupConversationId) {
        requireRole(identity, authEnabled, "eddi-editor");
        try {
            requireConversationOwner(groupConversationId);
            groupConversationService.deleteGroupConversation(groupConversationId);
            return "Deleted group conversation " + groupConversationId;
        } catch (ForbiddenException e) {
            return accessDenied("delete_group_conversation", groupConversationId);
        } catch (Exception e) {
            LOGGER.errorf("delete_group_conversation failed: %s", e.getMessage());
            return errorJson(e.getMessage());
        }
    }

    // --- Follow-up Operations ---

    @Blocking
    @Tool(description = "Ask a follow-up question to a specific member agent in a "
            + "completed group conversation. The agent retains full context from "
            + "the discussion. Both the question and response are recorded on the "
            + "group transcript. Works for any member including the moderator. "
            + "The targetAgentId accepts either an agent ID or a member's display name.")
    public String followup_with_member(
                                       @ToolArg(description = "Group conversation ID") String groupConversationId,
                                       @ToolArg(description = "Agent ID or display name of the member to address") String targetAgentId,
                                       @ToolArg(description = "The follow-up question") String question) {
        requireRole(identity, authEnabled, "eddi-viewer");
        try {
            requireConversationOwner(groupConversationId);
            GroupConversation gc = groupConversationService.followUpWithMember(
                    groupConversationId, targetAgentId, question);
            return jsonSerialization.serialize(gc);
        } catch (ForbiddenException e) {
            return accessDenied("followup_with_member", groupConversationId);
        } catch (Exception e) {
            LOGGER.errorf("followup_with_member failed: %s", LogSanitizer.sanitize(e.getMessage()));
            return errorJson(e.getMessage());
        }
    }

    @Blocking
    @Tool(description = "Continue a completed group conversation with a new question. "
            + "All agents re-run through the full discussion phases, retaining memory "
            + "of prior rounds. The round counter increments. Returns the updated "
            + "GroupConversation with new synthesis.")
    public String continue_group_discussion(
                                            @ToolArg(description = "Group conversation ID") String groupConversationId,
                                            @ToolArg(description = "The follow-up question for the group") String question) {
        requireRole(identity, authEnabled, "eddi-viewer");
        try {
            requireConversationOwner(groupConversationId);
            GroupConversation gc = groupConversationService.continueDiscussion(
                    groupConversationId, question, null);
            return jsonSerialization.serialize(gc);
        } catch (ForbiddenException e) {
            return accessDenied("continue_group_discussion", groupConversationId);
        } catch (Exception e) {
            LOGGER.errorf("continue_group_discussion failed: %s", LogSanitizer.sanitize(e.getMessage()));
            return errorJson(e.getMessage());
        }
    }

    @Tool(description = "Close a group conversation permanently. Ends all member "
            + "conversations and cleans up dynamically-created agents. No further "
            + "follow-ups or continuations are possible after closing. "
            + "Returns the closed GroupConversation.")
    public String close_group_conversation(
                                           @ToolArg(description = "Group conversation ID") String groupConversationId) {
        requireRole(identity, authEnabled, "eddi-editor");
        try {
            requireConversationOwner(groupConversationId);
            GroupConversation gc = groupConversationService.closeGroupConversation(groupConversationId);
            return jsonSerialization.serialize(gc);
        } catch (ForbiddenException e) {
            return accessDenied("close_group_conversation", groupConversationId);
        } catch (Exception e) {
            LOGGER.errorf("close_group_conversation failed: %s", LogSanitizer.sanitize(e.getMessage()));
            return errorJson(e.getMessage());
        }
    }
}
