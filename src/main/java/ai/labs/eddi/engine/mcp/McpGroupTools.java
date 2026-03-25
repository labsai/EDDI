package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.groups.IRestAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
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

    // --- Group Config CRUD ---

    @Tool(description = "List all agent group configurations")
    public String list_groups(@ToolArg(description = "Filter by name (optional)") String filter,
            @ToolArg(description = "Page index (default 0)") String index, @ToolArg(description = "Page size (default 20)") String limit) {
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

    @Tool(description = "Read a specific agent group configuration")
    public String read_group(@ToolArg(description = "Group configuration ID") String groupId,
            @ToolArg(description = "Version (0 = latest)") String version) {
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

    @Tool(description = "Create a new agent group. Styles: ROUND_TABLE, " + "PEER_REVIEW, DEVIL_ADVOCATE, DELPHI, DEBATE.")
    public String create_group(@ToolArg(description = "Group name") String name, @ToolArg(description = "Group description") String description,
            @ToolArg(description = "Comma-separated agent IDs") String memberAgentIds,
            @ToolArg(description = "Comma-separated display names") String memberDisplayNames,
            @ToolArg(description = "Moderator agent ID (optional)") String moderatorAgentId,
            @ToolArg(description = "Discussion style: ROUND_TABLE, PEER_REVIEW, "
                    + "DEVIL_ADVOCATE, DELPHI, DEBATE (default ROUND_TABLE)") String style,
            @ToolArg(description = "Max rounds for ROUND_TABLE/DELPHI (default 2)") String maxRounds) {
        try {
            AgentGroupConfiguration config = new AgentGroupConfiguration();
            config.setName(name);
            config.setDescription(description);

            // Parse members
            String[] agentIds = memberAgentIds.split(",");
            String[] displayNames = memberDisplayNames != null ? memberDisplayNames.split(",") : new String[0];
            List<GroupMember> members = new ArrayList<>();
            for (int i = 0; i < agentIds.length; i++) {
                String displayName = i < displayNames.length ? displayNames[i].trim() : "Agent " + (i + 1);
                members.add(new GroupMember(agentIds[i].trim(), displayName, i + 1, null));
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

            // Default protocol (error handling only)
            config.setProtocol(new ProtocolConfig(60, ProtocolConfig.MemberFailurePolicy.SKIP, 2, ProtocolConfig.MemberUnavailablePolicy.SKIP));

            Response response = groupStore.createGroup(config);
            String location = response.getLocation() != null ? response.getLocation().toString() : "";
            String groupId = extractIdFromLocation(location);
            return "Created group '%s' (style=%s) with ID: %s (%d members)".formatted(name, discussionStyle, groupId, members.size());
        } catch (Exception e) {
            LOGGER.errorf("create_group failed: %s", e.getMessage());
            return errorJson(e.getMessage());
        }
    }

    @Tool(description = "Update an existing agent group configuration")
    public String update_group(@ToolArg(description = "Group ID") String groupId, @ToolArg(description = "Version (0 = latest)") String version,
            @ToolArg(description = "JSON body of the updated configuration") String configJson) {
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
    public String delete_group(@ToolArg(description = "Group ID") String groupId, @ToolArg(description = "Version (0 = latest)") String version) {
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

    @Tool(description = "Start a multi-agent group discussion using the " + "group's configured discussion style.")
    public String discuss_with_group(@ToolArg(description = "Group configuration ID") String groupId,
            @ToolArg(description = "The question to discuss") String question, @ToolArg(description = "User ID (optional)") String userId) {
        try {
            String user = userId != null && !userId.isBlank() ? userId : "mcp-client";
            GroupConversation gc = groupConversationService.discuss(groupId, question, user, 0);
            return jsonSerialization.serialize(gc);
        } catch (Exception e) {
            LOGGER.errorf("discuss_with_group failed: %s", e.getMessage());
            return errorJson(e.getMessage());
        }
    }

    @Tool(description = "Read a group conversation transcript")
    public String read_group_conversation(@ToolArg(description = "Group conversation ID") String groupConversationId) {
        try {
            GroupConversation gc = groupConversationService.readGroupConversation(groupConversationId);
            return jsonSerialization.serialize(gc);
        } catch (Exception e) {
            LOGGER.errorf("read_group_conversation failed: %s", e.getMessage());
            return errorJson(e.getMessage());
        }
    }
}
