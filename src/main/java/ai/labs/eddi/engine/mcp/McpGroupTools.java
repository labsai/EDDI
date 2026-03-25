package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.groups.IRestAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IGroupConversationService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
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

    @Inject
    public McpGroupTools(IRestAgentGroupStore groupStore, IGroupConversationService groupConversationService, IJsonSerialization jsonSerialization) {
        this.groupStore = groupStore;
        this.groupConversationService = groupConversationService;
        this.jsonSerialization = jsonSerialization;
    }

    // --- Discovery ---

    @Tool(description = "Describe all available discussion styles for agent " + "groups. Returns the name, phase flow, and recommended use case "
            + "for each style (ROUND_TABLE, PEER_REVIEW, DEVIL_ADVOCATE, " + "DELPHI, DEBATE). Call this before create_group to choose the "
            + "right style.")
    public String describe_discussion_styles() {
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

                ## Nested Groups (Group-of-Groups)
                Members can be other groups (memberTypes=GROUP). The sub-group runs its
                own full discussion and its synthesized answer becomes the member's response.
                Use this for parallel review panels, red-team vs blue-team, or tournament brackets.

                ## Parameters
                - maxRounds: controls repeat count for ROUND_TABLE and DELPHI (default 2)
                - moderatorAgentId: required for synthesis/judging phase
                - memberRoles: comma-separated roles matching member positions
                - memberTypes: comma-separated types: AGENT (default) or GROUP
                """;
    }

    // --- Group Config CRUD ---

    @Tool(description = "List all agent group configurations. Returns " + "descriptors with name, ID, and last modified date.")
    public String list_groups(@ToolArg(description = "Filter by group name (optional)") String filter,
            @ToolArg(description = "Page index, 0-based (default 0)") String index, @ToolArg(description = "Page size (default 20)") String limit) {
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
            @ToolArg(description = "Comma-separated member IDs (agent IDs or " + "group IDs depending on memberTypes)") String memberAgentIds,
            @ToolArg(description = "Comma-separated display names (optional)") String memberDisplayNames,
            @ToolArg(description = "Comma-separated member roles: PARTICIPANT, " + "DEVIL_ADVOCATE, PRO, CON (optional)") String memberRoles,
            @ToolArg(description = "Comma-separated member types: AGENT " + "(default) or GROUP for nested groups (optional)") String memberTypes,
            @ToolArg(description = "Moderator agent ID (optional)") String moderatorAgentId,
            @ToolArg(description = "Discussion style: ROUND_TABLE, PEER_REVIEW, "
                    + "DEVIL_ADVOCATE, DELPHI, DEBATE (default ROUND_TABLE)") String style,
            @ToolArg(description = "Max rounds (default 2)") String maxRounds) {
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

            // Default protocol
            config.setProtocol(new ProtocolConfig(60, ProtocolConfig.MemberFailurePolicy.SKIP, 2, ProtocolConfig.MemberUnavailablePolicy.SKIP));

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

    @Tool(description = "Start a structured multi-agent discussion. All " + "configured member agents will participate using the group's "
            + "discussion style. Returns the full transcript with each " + "agent's contributions organized by phase.")
    public String discuss_with_group(@ToolArg(description = "Group configuration ID (from create_group " + "or list_groups)") String groupId,
            @ToolArg(description = "The question or topic for the group to " + "discuss") String question,
            @ToolArg(description = "User ID (optional, defaults to " + "'mcp-client')") String userId) {
        try {
            String user = userId != null && !userId.isBlank() ? userId : "mcp-client";
            GroupConversation gc = groupConversationService.discuss(groupId, question, user, 0);
            return jsonSerialization.serialize(gc);
        } catch (Exception e) {
            LOGGER.errorf("discuss_with_group failed: %s", e.getMessage());
            return errorJson(e.getMessage());
        }
    }

    @Tool(description = "Read a group conversation transcript including all " + "phases, agent contributions, and synthesized answer.")
    public String read_group_conversation(
            @ToolArg(description = "Group conversation ID (from " + "discuss_with_group or list_group_conversations)") String groupConversationId) {
        try {
            GroupConversation gc = groupConversationService.readGroupConversation(groupConversationId);
            return jsonSerialization.serialize(gc);
        } catch (Exception e) {
            LOGGER.errorf("read_group_conversation failed: %s", e.getMessage());
            return errorJson(e.getMessage());
        }
    }

    @Tool(description = "List past group conversation transcripts for a " + "group. Returns conversation IDs, state, question, and " + "timestamps.")
    public String list_group_conversations(@ToolArg(description = "Group configuration ID") String groupId,
            @ToolArg(description = "Page index, 0-based (default 0)") String index, @ToolArg(description = "Page size (default 20)") String limit) {
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
}
