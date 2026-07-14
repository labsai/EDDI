/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.audit.rest.IRestAuditStore;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.model.*;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.engine.model.LogEntry;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.runtime.BoundedLogStore;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.utils.LogSanitizer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.engine.mcp.McpToolUtils.*;

/**
 * MCP tools for conversing with EDDI agents. Exposes Agent listing,
 * conversation management, and messaging as MCP-compliant tools via the Quarkus
 * MCP Server extension.
 *
 * <p>
 * Phase 8a — Item 35: Agent Conversations MCP Server (5 SP)
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpConversationTools {

    private static final Logger LOGGER = Logger.getLogger(McpConversationTools.class);
    private static final int CONVERSATION_TIMEOUT_SECONDS = 60;
    /**
     * Page size for descriptor scans — the conversation descriptor store's own cap.
     */
    private static final int MAX_DESCRIPTOR_FETCH = 100;
    /**
     * How many descriptors an owner-filtered listing will scan before giving up.
     * The store has no owner-scoped query, so a caller's conversations can only be
     * found by scanning; this bounds that scan, and a listing that stops here says
     * so ({@code incomplete}) instead of reporting a partial list as complete.
     */
    private static final int MAX_OWNER_SCAN = 500;

    private final IConversationService conversationService;
    private final IRestAgentAdministration agentAdmin;
    private final IRestAgentStore agentStore;
    private final IRestInterfaceFactory restInterfaceFactory;
    private final IJsonSerialization jsonSerialization;
    private final BoundedLogStore boundedLogStore;
    private final IRestAuditStore auditStore;
    private final IRestAgentTriggerStore agentTriggerStore;
    private final IUserConversationStore userConversationStore;
    private final IRestAgentEngine restAgentEngine;
    private final SecurityIdentity identity;
    private final ConversationAccessGuard conversationAccessGuard;
    private final boolean authEnabled;

    @Inject
    public McpConversationTools(IConversationService conversationService, IRestAgentAdministration agentAdmin, IRestAgentStore agentStore,
            IRestInterfaceFactory restInterfaceFactory, IJsonSerialization jsonSerialization, BoundedLogStore boundedLogStore,
            IRestAuditStore auditStore, IRestAgentTriggerStore agentTriggerStore, IUserConversationStore userConversationStore,
            IRestAgentEngine restAgentEngine, SecurityIdentity identity, ConversationAccessGuard conversationAccessGuard,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.conversationService = conversationService;
        this.agentAdmin = agentAdmin;
        this.agentStore = agentStore;
        this.restInterfaceFactory = restInterfaceFactory;
        this.jsonSerialization = jsonSerialization;
        this.boundedLogStore = boundedLogStore;
        this.auditStore = auditStore;
        this.agentTriggerStore = agentTriggerStore;
        this.userConversationStore = userConversationStore;
        this.restAgentEngine = restAgentEngine;
        this.identity = identity;
        this.conversationAccessGuard = conversationAccessGuard;
        this.authEnabled = authEnabled;
    }

    /**
     * Uniform, non-leaking denial for an MCP call on someone else's conversation.
     * The message never distinguishes "not yours" from "does not exist", so it
     * cannot be used to probe for the existence of other users' conversations.
     */
    private String accessDenied(String tool, String conversationId) {
        LOGGER.infof("%s denied: caller does not own conversation %s", tool, LogSanitizer.sanitize(conversationId));
        return errorJson("Access denied: you do not own this conversation");
    }

    @Tool(name = "list_agents", description = "List all deployed agents with their status, version, and name. "
            + "Returns a JSON array of Agent deployment statuses.")
    public String listAgents(@ToolArg(description = "Environment: 'production' (default), 'production', or 'test'") String environment) {
        requireRole(identity, authEnabled, "eddi-viewer");
        try {
            var env = parseEnvironment(environment);
            List<AgentDeploymentStatus> statuses = agentAdmin.getDeploymentStatuses(env);
            return jsonSerialization.serialize(statuses);
        } catch (Exception e) {
            LOGGER.error("MCP list_agents failed", e);
            return errorJson("Failed to list agents: " + e.getMessage());
        }
    }

    @Tool(name = "list_agent_configs", description = "List all Agent configurations (including those not yet deployed). "
            + "Returns a JSON array of Agent descriptors with name, description, and IDs.")
    public String listAgentConfigs(@ToolArg(description = "Optional filter string to search Agent names") String filter,
                                   @ToolArg(description = "Maximum number of results (default 20)") Integer limit) {
        requireRole(identity, authEnabled, "eddi-viewer");
        try {
            int limitInt = limit != null ? limit : 20;
            String filterStr = filter != null ? filter : "";
            List<DocumentDescriptor> descriptors = agentStore.readAgentDescriptors(filterStr, 0, limitInt);
            return jsonSerialization.serialize(descriptors);
        } catch (Exception e) {
            LOGGER.error("MCP list_agent_configs failed", e);
            return errorJson("Failed to list Agent configs: " + e.getMessage());
        }
    }

    @Tool(name = "create_conversation", description = "Start a new conversation with a deployed agent. "
            + "Returns the conversationId which you need for subsequent talk_to_agent calls. "
            + "Tip: Use chat_with_agent instead if you want to send a message immediately.")
    public String createConversation(@ToolArg(description = "Agent ID (required)") String agentId,
                                     @ToolArg(description = "Environment: 'production' (default), 'production', or 'test'") String environment) {
        requireRole(identity, authEnabled, "eddi-viewer");
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        try {
            var env = parseEnvironment(environment);
            // Stamp the caller as owner (null when authorization is disabled — the
            // engine then assigns an anonymous id, as before). Without this, an
            // MCP-created conversation would belong to nobody and its own creator
            // could never read it back once the ownership gate applies.
            String ownerUserId = conversationAccessGuard.resolveOwnerUserId(null);
            ConversationResult result = conversationService.startConversation(env, agentId, ownerUserId, Collections.emptyMap());
            return jsonSerialization.serialize(Map.of("conversationId", result.conversationId(), "conversationUri",
                    result.conversationUri().toString(), "agentId", agentId, "environment", env.name()));
        } catch (Exception e) {
            LOGGER.error("MCP create_conversation failed for Agent " + agentId, e);
            return errorJson("Failed to create conversation: " + e.getMessage());
        }
    }

    @Blocking
    @Tool(name = "talk_to_agent", description = "Send a message to a Agent in an existing conversation and get the agent's response. "
            + "You must first call create_conversation to get a conversationId, " + "or use chat_with_agent for a single-call alternative.")
    public String talkToAgent(@ToolArg(description = "Agent ID (required)") String agentId,
                              @ToolArg(description = "Conversation ID from create_conversation (required)") String conversationId,
                              @ToolArg(description = "The user message to send to the Agent (required)") String message,
                              @ToolArg(description = "Environment: 'production' (default), 'production', or 'test'") String environment) {
        requireRole(identity, authEnabled, "eddi-viewer");
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        if (conversationId == null || conversationId.isBlank())
            return errorJson("conversationId is required");
        if (message == null || message.isBlank())
            return errorJson("message is required");
        try {
            // Driving someone else's conversation is a write — gate it exactly like
            // the REST say() does, or a viewer could inject turns into another
            // user's conversation and read the agent's reply.
            conversationAccessGuard.requireConversationOwner(conversationId);

            var snapshot = sendMessageAndWait(conversationId, message);

            // Finding 25: this turn itself paused for human approval — return a
            // structured PAUSED signal (not BUSY/error) so the MCP client can inform
            // its user and re-invoke after a reviewer decides.
            if (snapshot != null && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN) {
                return pausedForApprovalJson(agentId, conversationId, snapshot);
            }

            var result = buildConversationResponse(snapshot, null);
            return jsonSerialization.serialize(result);
        } catch (ForbiddenException e) {
            return accessDenied("talk_to_agent", conversationId);
        } catch (IConversationService.ConversationAwaitingApprovalException e) {
            // Finding 25: already-paused at submit — say() rejects the input
            // synchronously. Report the pending approval instead of a generic error.
            return pausedForApprovalJson(agentId, conversationId);
        } catch (ConversationSkippedException e) {
            // H7: the turn was dropped without processing — report busy/not-active
            // instead of a stale previous-turn reply.
            if (e.state() == ConversationState.AWAITING_HUMAN) {
                // Finding 25: queued-then-paused — a deliberate pause, not busy.
                return pausedForApprovalJson(agentId, conversationId);
            }
            return skippedResultJson(conversationId, e.state());
        } catch (Exception e) {
            LOGGER.error("MCP talk_to_agent failed for Agent " + agentId + " conversation " + conversationId, e);
            return errorJson("Failed to talk to agent: " + e.getMessage());
        }
    }

    @Blocking
    @Tool(name = "chat_with_agent", description = "Send a message to an agent, automatically creating a new conversation if needed. "
            + "This is the simplest way to interact with a Agent — combines create_conversation + "
            + "talk_to_agent into a single call. Returns the Agent response and conversationId " + "for follow-up messages.")
    public String chatWithAgent(@ToolArg(description = "Agent ID (required)") String agentId,
                                @ToolArg(description = "The user message to send to the Agent (required)") String message,
                                @ToolArg(description = "Conversation ID to continue (optional — creates new if omitted)") String conversationId,
                                @ToolArg(description = "Environment: 'production' (default), 'production', or 'test'") String environment) {
        requireRole(identity, authEnabled, "eddi-viewer");
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        if (message == null || message.isBlank())
            return errorJson("message is required");
        // O4: hoist convId out of the try so a freshly-created conversation id is
        // still in scope for the catch — otherwise a skip/pause after auto-creation
        // would report the (nullable) method arg and lose the real id.
        String convId = conversationId;
        try {
            var env = parseEnvironment(environment);

            // Step 1: Create conversation if not provided — stamping the caller as
            // owner. An existing conversation must instead be owned by the caller:
            // continuing another user's conversation is a write into it.
            if (convId == null || convId.isBlank()) {
                String ownerUserId = conversationAccessGuard.resolveOwnerUserId(null);
                ConversationResult convResult = conversationService.startConversation(env, agentId, ownerUserId, Collections.emptyMap());
                convId = convResult.conversationId();
            } else {
                conversationAccessGuard.requireConversationOwner(convId);
            }

            // Step 2: Send the message
            var snapshot = sendMessageAndWait(convId, message);

            // Finding 25: this turn itself paused for human approval — return a
            // structured PAUSED signal (not BUSY/error) so the MCP client can inform
            // its user and re-invoke after a reviewer decides.
            if (snapshot != null && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN) {
                return pausedForApprovalJson(agentId, convId, snapshot);
            }

            // Step 3: Return AI-agent-friendly summary + full snapshot
            var result = buildConversationResponse(snapshot, convId);
            result.putFirst("environment", env.name());
            result.putFirst("agentId", agentId);
            result.putFirst("conversationId", convId);
            return jsonSerialization.serialize(result);
        } catch (ForbiddenException e) {
            return accessDenied("chat_with_agent", convId);
        } catch (IConversationService.ConversationAwaitingApprovalException e) {
            // Finding 25: already-paused at submit — say() rejects the input
            // synchronously. Report the pending approval instead of a generic error.
            return pausedForApprovalJson(agentId, convId);
        } catch (ConversationSkippedException e) {
            // H7: the turn was dropped without processing — report busy/not-active
            // instead of a stale previous-turn reply. convId carries the real id even
            // when auto-created above (O4).
            if (e.state() == ConversationState.AWAITING_HUMAN) {
                // Finding 25: queued-then-paused — a deliberate pause, not busy.
                return pausedForApprovalJson(agentId, convId);
            }
            return skippedResultJson(convId, e.state());
        } catch (Exception e) {
            LOGGER.error("MCP chat_with_agent failed for Agent " + agentId, e);
            return errorJson("Failed to chat with agent: " + e.getMessage());
        }
    }

    @Tool(name = "read_conversation", description = "Read conversation history and memory. "
            + "Returns the conversation memory snapshot. Use returningFields to limit "
            + "output size, or use read_conversation_log for a human-readable summary.")
    public String readConversation(@ToolArg(description = "Agent ID (deprecated — ignored, resolved from conversation)") String agentId,
                                   @ToolArg(description = "Conversation ID (required)") String conversationId,
                                   @ToolArg(description = "Deprecated — ignored, resolved from conversation") String environment,
                                   @ToolArg(description = "Return only the current (latest) step? (default: true)") Boolean currentStepOnly,
                                   @ToolArg(description = "Return detailed internal data? (default: false)") Boolean returnDetailed,
                                   @ToolArg(description = "Comma-separated list of fields to return (e.g. 'input,output,actions'). "
                                           + "Empty = all fields.") String returningFields) {
        requireRole(identity, authEnabled, "eddi-viewer");
        try {
            conversationAccessGuard.requireConversationOwner(conversationId);

            boolean stepOnly = currentStepOnly != null ? currentStepOnly : true;
            boolean detailed = returnDetailed != null ? returnDetailed : false;

            List<String> fields = Collections.emptyList();
            if (returningFields != null && !returningFields.isBlank()) {
                fields = List.of(returningFields.split(","));
            }

            var snapshot = conversationService.readConversation(conversationId, detailed, stepOnly, fields);

            // Apply field-level filtering on conversationOutputs when specific
            // field names are requested (e.g. "input", "output", "actions").
            // The service layer only handles section-level filtering
            // (conversationSteps, conversationOutputs, conversationProperties).
            // Create filtered copies to avoid mutating the original snapshot.
            if (!fields.isEmpty() && snapshot.getConversationOutputs() != null) {
                var trimmedFields = fields.stream().map(String::trim).toList();
                // If the caller requested a section-level name (e.g. "conversationOutputs"),
                // skip field-level filtering — the caller wants the full section.
                boolean requestedFullSection = trimmedFields.stream()
                        .anyMatch(f -> f.equals("conversationOutputs") || f.equals("conversationSteps")
                                || f.equals("conversationProperties"));
                if (!requestedFullSection) {
                    var filteredOutputs = snapshot.getConversationOutputs().stream()
                            .map(output -> {
                                var filtered = new ai.labs.eddi.engine.memory.model.ConversationOutput();
                                output.forEach((key, value) -> {
                                    if (key instanceof String s && trimmedFields.stream().anyMatch(f -> s.equals(f) || s.startsWith(f + ":"))) {
                                        filtered.put(key, value);
                                    }
                                });
                                return filtered;
                            }).toList();
                    snapshot.setConversationOutputs(filteredOutputs);
                }
            }

            return jsonSerialization.serialize(snapshot);
        } catch (ForbiddenException e) {
            return accessDenied("read_conversation", conversationId);
        } catch (Exception e) {
            LOGGER.error("MCP read_conversation failed for conversation " + conversationId, e);
            return errorJson("Failed to read conversation: " + e.getMessage());
        }
    }

    @Tool(name = "read_conversation_log", description = "Read conversation log as formatted text. "
            + "Returns the conversation history in a human-readable format. "
            + "This is the preferred tool for reviewing what was said in a conversation.")
    public String readConversationLog(@ToolArg(description = "Conversation ID (required)") String conversationId,
                                      @ToolArg(description = "Number of recent steps to include (default: all)") Integer logSize) {
        requireRole(identity, authEnabled, "eddi-viewer");
        try {
            conversationAccessGuard.requireConversationOwner(conversationId);

            var result = conversationService.readConversationLog(conversationId, "text", logSize != null && logSize > 0 ? logSize : null);
            return result.content().toString();
        } catch (ForbiddenException e) {
            return accessDenied("read_conversation_log", conversationId);
        } catch (Exception e) {
            LOGGER.error("MCP read_conversation_log failed for conversation " + conversationId, e);
            return errorJson("Failed to read conversation log: " + e.getMessage());
        }
    }

    @Tool(name = "list_conversations", description = "List conversations for a specific agent. "
            + "Returns conversation descriptors with IDs, creation time, and state. "
            + "Useful for finding conversation IDs without knowing them beforehand.")
    public String listConversations(@ToolArg(description = "Agent ID (required)") String agentId,
                                    @ToolArg(description = "Agent version (default: latest)") Integer agentVersion,
                                    @ToolArg(description = "Filter by state: 'READY', 'IN_PROGRESS', "
                                            + "'ENDED', 'ERROR' (default: all)") String conversationState,
                                    @ToolArg(description = "Maximum number of results (default: 20, max: 100)") Integer limit) {
        requireRole(identity, authEnabled, "eddi-viewer");
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        try {
            int limitInt = Math.min(limit != null ? limit : 20, 100);
            int ver = agentVersion != null ? agentVersion : 0;

            // Parse ConversationState enum if provided
            ConversationState state = null;
            if (conversationState != null && !conversationState.isBlank()) {
                try {
                    state = ConversationState.valueOf(conversationState.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    return errorJson("Invalid conversationState: " + conversationState + ". Valid values: READY, IN_PROGRESS, ENDED, ERROR");
                }
            }

            IRestConversationStore convStore;
            try {
                convStore = restInterfaceFactory.get(IRestConversationStore.class);
            } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
                return errorJson("Failed to get conversation store: " + e.getMessage());
            }

            // Owner-scoping: a non-admin lists only their own conversations. The
            // descriptor store has no owner-scoped query, so we post-filter. A single
            // page would silently starve a personal list — on a shared agent the newest
            // page can be entirely other users' conversations, which would report an
            // empty list as if the caller had none. So we scan forward until the
            // requested limit is filled or the scan budget is spent, and say so when we
            // stop early rather than passing a partial list off as complete.
            boolean seesAll = conversationAccessGuard.seesAllConversations();

            List<ConversationDescriptor> visible;
            boolean incomplete = false;
            if (seesAll) {
                visible = convStore.readConversationDescriptors(0, limitInt, null, null, agentId, ver == 0 ? null : ver, state, null);
            } else {
                visible = new ArrayList<>();
                // The store's own paging skips deleted descriptors, so its cursor can
                // outrun the rows it hands back and an offset-based scan may re-read a
                // row. Dedupe by resource URI so a conversation is never listed twice.
                var seen = new HashSet<URI>();
                int scanned = 0;
                while (visible.size() < limitInt && scanned < MAX_OWNER_SCAN) {
                    var page = convStore.readConversationDescriptors(scanned, MAX_DESCRIPTOR_FETCH, null, null, agentId,
                            ver == 0 ? null : ver, state, null);
                    if (page.isEmpty()) {
                        break; // store exhausted — the list is complete
                    }
                    scanned += page.size();
                    for (var descriptor : page) {
                        if (visible.size() >= limitInt) {
                            break;
                        }
                        if (descriptor.getResource() != null && !seen.add(descriptor.getResource())) {
                            continue; // already listed from an earlier page
                        }
                        if (conversationAccessGuard.canAccessConversation(descriptor.getUserId())) {
                            visible.add(descriptor);
                        }
                    }
                    if (page.size() < MAX_DESCRIPTOR_FETCH) {
                        break; // last page — the list is complete
                    }
                }
                // We stopped on the scan budget rather than on the store running out,
                // so conversations owned by the caller may lie beyond what we scanned.
                incomplete = visible.size() < limitInt && scanned >= MAX_OWNER_SCAN;
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("agentId", agentId);
            result.put("count", visible.size());
            if (incomplete) {
                result.put("incomplete", true);
                result.put("note", "Only the " + MAX_OWNER_SCAN + " most recent conversations of this agent were scanned; "
                        + "older conversations of yours may exist but are not listed.");
            }
            result.put("conversations", visible);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP list_conversations failed for Agent " + agentId, e);
            return errorJson("Failed to list conversations: " + e.getMessage());
        }
    }

    @Tool(name = "get_agent", description = "Get an agent's full configuration including its packages, name, and description. "
            + "Returns the AgentConfiguration JSON with all package references.")
    public String getAgent(@ToolArg(description = "Agent ID (required)") String agentId,
                           @ToolArg(description = "Version number (default: latest)") Integer version) {
        requireRole(identity, authEnabled, "eddi-viewer");
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        try {
            int ver = version != null ? version : 1;
            AgentConfiguration config = agentStore.readAgent(agentId, ver);
            if (config == null) {
                return errorJson("Agent not found: " + agentId + " version " + ver);
            }

            // Read descriptor for name/description (direct by ID, not N+1)
            var result = new LinkedHashMap<String, Object>();
            result.put("agentId", agentId);
            result.put("version", ver);
            try {
                DocumentDescriptor descriptor = McpToolUtils.getRestStore(restInterfaceFactory, IRestDocumentDescriptorStore.class)
                        .readDescriptor(agentId, ver);
                if (descriptor != null) {
                    result.put("name", descriptor.getName());
                    result.put("description", descriptor.getDescription());
                }
            } catch (Exception e) {
                LOGGER.debug("Could not read Agent descriptor", e);
            }
            result.put("configuration", config);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP get_agent failed for Agent " + agentId, e);
            return errorJson("Failed to get agent: " + e.getMessage());
        }
    }

    // ==================== Phase 8a.2: Diagnostic Tools ====================

    @Tool(name = "read_agent_logs", description = "Read recent server-side logs for a Agent or conversation. "
            + "Returns workflow execution logs, LLM provider errors, timeouts, and internal diagnostics "
            + "that are NOT visible in conversation memory. Essential for debugging 'why did the Agent fail?' "
            + "Filter by agentId, conversationId, and/or log level.")
    public String readAgentLogs(@ToolArg(description = "Filter by Agent ID (optional)") String agentId,
                                @ToolArg(description = "Filter by conversation ID (optional)") String conversationId,
                                @ToolArg(description = "Filter by log level: 'ERROR', 'WARN', 'INFO', 'DEBUG' (optional)") String level,
                                @ToolArg(description = "Maximum number of log entries to return (default: 50)") Integer limit) {
        requireRole(identity, authEnabled, "eddi-viewer");
        try {
            int limitInt = limit != null ? limit : 50;
            String agentFilter = (agentId != null && !agentId.isBlank()) ? agentId : null;
            String convFilter = (conversationId != null && !conversationId.isBlank()) ? conversationId : null;
            String levelFilter = (level != null && !level.isBlank()) ? level.toUpperCase() : null;

            // Logs scoped to one conversation are that conversation's data.
            // (An unscoped/agent-only read still returns cross-user server logs to any
            // viewer — a separate, coarser gap tracked outside this change.)
            if (convFilter != null) {
                conversationAccessGuard.requireConversationOwner(convFilter);
            }

            List<LogEntry> entries = boundedLogStore.getEntries(agentFilter, convFilter, levelFilter, limitInt);

            var result = new LinkedHashMap<String, Object>();
            result.put("count", entries.size());
            result.put("limit", limitInt);
            if (agentFilter != null)
                result.put("agentId", agentFilter);
            if (convFilter != null)
                result.put("conversationId", convFilter);
            if (levelFilter != null)
                result.put("level", levelFilter);
            result.put("entries", entries);
            return jsonSerialization.serialize(result);
        } catch (ForbiddenException e) {
            return accessDenied("read_agent_logs", conversationId);
        } catch (Exception e) {
            LOGGER.error("MCP read_agent_logs failed", e);
            return errorJson("Failed to read Agent logs: " + e.getMessage());
        }
    }

    @Tool(name = "read_audit_trail", description = "Read the audit trail for a conversation. "
            + "Returns per-task execution records including: taskId, taskType, input/output data, "
            + "LLM details (model, prompt, tokens, cost), tool calls, actions emitted, and timing. "
            + "This shows EXACTLY what happened at each workflow step — essential for optimizing Agent behavior.")
    public String readAuditTrail(@ToolArg(description = "Conversation ID (required)") String conversationId,
                                 @ToolArg(description = "Maximum number of entries to return (default: 20)") Integer limit) {
        requireRole(identity, authEnabled, "eddi-viewer");
        if (conversationId == null || conversationId.isBlank())
            return errorJson("conversationId is required");
        try {
            // The audit trail carries that conversation's prompts, tool calls and
            // costs — at least as sensitive as the transcript itself.
            conversationAccessGuard.requireConversationOwner(conversationId);

            int limitInt = limit != null ? limit : 20;
            List<AuditEntry> entries = auditStore.getAuditTrail(conversationId, 0, limitInt);

            var result = new LinkedHashMap<String, Object>();
            result.put("conversationId", conversationId);
            result.put("count", entries.size());
            result.put("entries", entries);
            return jsonSerialization.serialize(result);
        } catch (ForbiddenException e) {
            return accessDenied("read_audit_trail", conversationId);
        } catch (Exception e) {
            LOGGER.error("MCP read_audit_trail failed for conversation " + conversationId, e);
            return errorJson("Failed to read audit trail: " + e.getMessage());
        }
    }

    @Tool(name = "discover_agents", description = "Discover deployed agents with their capabilities. "
            + "Returns an enriched list of deployed agents, cross-referenced with intent mappings "
            + "from Agent triggers. Each Agent entry includes: agentId, name, description, version, status, "
            + "and any intents it serves. This is the best way to find agents by purpose.")
    public String discoverAgents(@ToolArg(description = "Optional filter string to search Agent names") String filter,
                                 @ToolArg(description = "Environment: 'production' (default), 'production', or 'test'") String environment) {
        requireRole(identity, authEnabled, "eddi-viewer");
        try {
            var env = parseEnvironment(environment);
            List<AgentDeploymentStatus> statuses = agentAdmin.getDeploymentStatuses(env);

            // Build agentId -> intents mapping from triggers
            Map<String, List<String>> agentIntents = new LinkedHashMap<>();
            try {
                List<AgentTriggerConfiguration> triggers = agentTriggerStore.readAllAgentTriggers();
                for (var trigger : triggers) {
                    for (var deployment : trigger.getAgentDeployments()) {
                        agentIntents.computeIfAbsent(deployment.getAgentId(), k -> new ArrayList<>()).add(trigger.getIntent());
                    }
                }
            } catch (Exception e) {
                LOGGER.warnv("Could not read Agent triggers for discovery: {0}", e.getMessage());
            }

            // Build enriched results
            var agents = new ArrayList<Map<String, Object>>();
            for (var status : statuses) {
                if (status.getDescriptor() != null && status.getDescriptor().isDeleted())
                    continue;

                String name = status.getDescriptor() != null ? status.getDescriptor().getName() : "";
                String desc = status.getDescriptor() != null ? status.getDescriptor().getDescription() : "";

                // Apply name filter if provided
                if (filter != null && !filter.isBlank()) {
                    boolean matches = (name != null && name.toLowerCase().contains(filter.toLowerCase()))
                            || (desc != null && desc.toLowerCase().contains(filter.toLowerCase()));
                    if (!matches)
                        continue;
                }

                var agentMap = new LinkedHashMap<String, Object>();
                agentMap.put("agentId", status.getAgentId());
                agentMap.put("name", name);
                agentMap.put("description", desc);
                agentMap.put("version", status.getAgentVersion());
                agentMap.put("status", status.getStatus().name());
                agentMap.put("environment", status.getEnvironment().name());
                List<String> intents = agentIntents.get(status.getAgentId());
                if (intents != null && !intents.isEmpty()) {
                    agentMap.put("intents", intents);
                }
                agents.add(agentMap);
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("count", agents.size());
            result.put("agents", agents);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP discover_agents failed", e);
            return errorJson("Failed to discover agents: " + e.getMessage());
        }
    }

    @Blocking
    @Tool(name = "chat_managed", description = "Send a message to a Agent using intent-based managed conversations. "
            + "Unlike chat_with_agent (which requires a agentId and creates multiple conversations), this "
            + "tool uses an 'intent' to find the right Agent and maintains exactly ONE conversation "
            + "per intent+userId — like a single chat window. The conversation is auto-created on "
            + "first message and reused on subsequent calls. Requires a Agent trigger to be configured "
            + "for the intent (see list_agent_triggers / create_agent_trigger).")
    public String chatManaged(@ToolArg(description = "Intent that maps to a Agent trigger (required). E.g. 'customer_support'") String intent,
                              @ToolArg(description = "User ID for conversation management (required)") String userId,
                              @ToolArg(description = "The user message to send (required)") String message,
                              @ToolArg(description = "Environment: 'production' (default), 'production', or 'test'") String environment) {
        requireRole(identity, authEnabled, "eddi-viewer");
        if (intent == null || intent.isBlank())
            return errorJson("intent is required");
        if (userId == null || userId.isBlank())
            return errorJson("userId is required");
        if (message == null || message.isBlank())
            return errorJson("message is required");

        try {
            var env = parseEnvironment(environment);

            // The userId here is a plain tool argument: without this check a caller
            // could name any userId and take over that user's managed conversation
            // for the intent. Admins may still act on another user's behalf.
            userId = conversationAccessGuard.resolveOwnerUserId(userId);

            // Step 1: Get or create the user conversation for this intent
            var userConversation = getOrCreateManagedConversation(intent, userId, env);
            var conversationId = userConversation.getConversationId();

            // Step 2: Send the message using the existing sendMessageAndWait helper.
            // Finding 25: a managed conversation already paused for human approval
            // throws synchronously — report the pending approval instead of hanging
            // on the timeout and returning a generic error on every call. The
            // mapping is intentionally preserved (NOT recreated) so re-invoking
            // after approval continues the same conversation.
            SimpleConversationMemorySnapshot snapshot;
            try {
                snapshot = sendMessageAndWait(conversationId, message);
            } catch (IConversationService.ConversationAwaitingApprovalException e) {
                return pausedForApprovalJson(intent, userId, userConversation.getAgentId(), conversationId);
            } catch (ConversationSkippedException e) {
                // H7: a busy-skip (AWAITING_HUMAN → pending approval; else busy /
                // not-active). Report the accurate state — never a stale reply.
                if (e.state() == ConversationState.AWAITING_HUMAN) {
                    return pausedForApprovalJson(intent, userId, userConversation.getAgentId(), conversationId);
                }
                return skippedResultJson(conversationId, e.state());
            }

            // Finding 25: this turn itself paused for approval — same structured
            // signal so the MCP client can inform its user and re-invoke later.
            if (snapshot != null && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN) {
                return pausedForApprovalJson(intent, userId, userConversation.getAgentId(), conversationId, snapshot);
            }

            // Step 3: Build response
            var result = buildConversationResponse(snapshot, conversationId);
            result.putFirst("intent", intent);
            result.putFirst("userId", userId);
            result.putFirst("agentId", userConversation.getAgentId());
            result.putFirst("conversationId", conversationId);
            result.putFirst("environment", userConversation.getEnvironment().name());
            return jsonSerialization.serialize(result);
        } catch (ForbiddenException e) {
            // Two ways in: naming another user's userId, or a stale intent→conversation
            // mapping pointing at a conversation the caller does not own (the ownership
            // check inside getConversationState). One message covers both without
            // disclosing which.
            LOGGER.infof("chat_managed denied for intent %s as user %s",
                    LogSanitizer.sanitize(intent), LogSanitizer.sanitize(userId));
            return errorJson("Access denied: you do not own this managed conversation");
        } catch (Exception e) {
            LOGGER.errorv("MCP chat_managed failed for intent={0}, userId={1}: {2}", intent, userId, e.getMessage());
            return errorJson("Failed to chat via managed agent: " + e.getMessage());
        }
    }

    /**
     * Structured, actionable JSON for a managed conversation awaiting human
     * approval (finding 25). The MCP client should relay this to its user rather
     * than retrying blindly — the pause is intentional and must not be
     * auto-cancelled. Re-invoke {@code chat_managed} with the same intent+userId
     * after a reviewer decides.
     */
    private String pausedForApprovalJson(String intent, String userId, String agentId, String conversationId) {
        return pausedForApprovalJson(intent, userId, agentId, conversationId, null);
    }

    private String pausedForApprovalJson(String intent, String userId, String agentId, String conversationId,
                                         SimpleConversationMemorySnapshot snapshot) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "PAUSED_FOR_APPROVAL");
        result.put("intent", intent);
        result.put("userId", userId);
        result.put("agentId", agentId);
        result.put("conversationId", conversationId);
        result.put("conversationState", ConversationState.AWAITING_HUMAN.name());
        // Name the MCP tool that resolves this gate so an LLM client can chain the
        // approval
        // instead of dropping to REST. resume_conversation handles both RULE and
        // TOOL_CALL pauses.
        result.put("suggestNextTool", "resume_conversation");
        // Task 13 (additive): a TOOL_CALL pause surfaces pauseType + tool NAMES.
        addToolPauseFields(result, snapshot);
        result.put("message", "The managed agent's conversation " + conversationId
                + " requires human approval before it can continue. A reviewer must decide via "
                + "POST /agents/" + conversationId + "/resume (APPROVED or REJECTED); "
                + "re-invoke chat_managed with the same intent and userId afterwards to continue.");
        try {
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            return errorJson("Conversation " + conversationId + " is awaiting human approval");
        }
    }

    /**
     * Agent-scoped counterpart of the intent-scoped
     * {@link #pausedForApprovalJson(String, String, String, String)} used by
     * chat_managed. Reports a deliberate HITL pause for the direct
     * agent-conversation tools (talk_to_agent / chat_with_agent) — a PAUSED signal
     * the MCP client should relay to its user, not retry blindly. Re-invoke the
     * same tool with the same conversationId after a reviewer decides.
     */
    private String pausedForApprovalJson(String agentId, String conversationId) {
        return pausedForApprovalJson(agentId, conversationId, null);
    }

    private String pausedForApprovalJson(String agentId, String conversationId, SimpleConversationMemorySnapshot snapshot) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "PAUSED_FOR_APPROVAL");
        result.put("agentId", agentId);
        result.put("conversationId", conversationId);
        result.put("conversationState", ConversationState.AWAITING_HUMAN.name());
        // Name the MCP tool that resolves this gate so an LLM client can chain the
        // approval
        // instead of dropping to REST. resume_conversation handles both RULE and
        // TOOL_CALL pauses.
        result.put("suggestNextTool", "resume_conversation");
        // Task 13 (additive): a TOOL_CALL pause surfaces pauseType + tool NAMES.
        addToolPauseFields(result, snapshot);
        result.put("message", "Conversation " + conversationId
                + " requires human approval before it can continue; resolve via POST /agents/"
                + conversationId + "/resume (APPROVED or REJECTED), then resend.");
        try {
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            return errorJson("Conversation " + conversationId + " is awaiting human approval");
        }
    }

    /**
     * Task 13 (additive): when the pause is a TOOL_CALL gate, add {@code pauseType}
     * and {@code tools} (gated tool NAMES only — never arguments, raw or redacted)
     * to the envelope map. A RULE pause (or an absent snapshot) leaves the map
     * unchanged, preserving the existing envelope shape and every existing field
     * (including {@code suggestNextTool}) for backward compatibility.
     */
    private void addToolPauseFields(Map<String, Object> result, SimpleConversationMemorySnapshot snapshot) {
        if (snapshot == null || !"TOOL_CALL".equals(snapshot.getHitlPauseType())) {
            return;
        }
        result.put("pauseType", "TOOL_CALL");
        var batch = snapshot.getHitlPendingToolCalls();
        if (batch != null && batch.getCalls() != null) {
            var toolNames = batch.getCalls().stream()
                    .map(ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall::getToolName)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (!toolNames.isEmpty()) {
                result.put("tools", toolNames);
            }
        }
    }

    /**
     * Get an existing user conversation for the intent+userId, or create a new one.
     * Mirrors the logic from {@code RestAgentManagement.initUserConversation}.
     */
    private UserConversation getOrCreateManagedConversation(String intent, String userId, Deployment.Environment env) throws Exception {

        // Try to read existing conversation
        UserConversation existing = null;
        try {
            existing = userConversationStore.readUserConversation(intent, userId);
        } catch (IResourceStore.ResourceStoreException ignored) {
            // Not found — will create
        }

        if (existing != null) {
            // Validate that the trigger still exists — if it was deleted,
            // the UserConversation is stale and should be cleaned up.
            // Note: readAgentTrigger throws ResourceNotFoundException via sneakyThrow
            // (bypasses checked exception analysis), so we catch Exception + instanceof.
            try {
                agentTriggerStore.readAgentTrigger(intent);
            } catch (Exception triggerEx) {
                if (triggerEx instanceof IResourceStore.ResourceNotFoundException) {
                    userConversationStore.deleteUserConversation(intent, userId);
                    throw new RuntimeException("Agent trigger for intent '" + intent + "' no longer exists");
                }
                throw triggerEx; // Rethrow transient DB errors
            }

            // Check if conversation has ended or no longer exists in the DB.
            // The UserConversation mapping may reference a conversation that was
            // deleted externally — handle gracefully by creating a fresh one.
            try {
                ConversationState state = restAgentEngine.getConversationState(existing.getConversationId());
                if (!ConversationState.ENDED.equals(state)) {
                    return existing;
                }
            } catch (IConversationService.ConversationNotFoundException stateEx) {
                // Conversation not found in DB — stale mapping, fall through to create fresh
                LOGGER.warnv("Stale UserConversation for intent={0}, userId={1}: conversation {2} not found, recreating",
                        intent, userId, existing.getConversationId());
            }
            // Ended or stale — delete and create fresh
            userConversationStore.deleteUserConversation(intent, userId);
        }

        // Create a new conversation from the Agent trigger
        AgentTriggerConfiguration trigger = agentTriggerStore.readAgentTrigger(intent);
        if (trigger == null || trigger.getAgentDeployments().isEmpty()) {
            throw new RuntimeException("No Agent trigger configured for intent: " + intent);
        }

        // Pick first deployment
        AgentDeployment deployment = trigger.getAgentDeployments().getFirst();
        String agentId = deployment.getAgentId();
        var usedEnv = deployment.getEnvironment() != null ? deployment.getEnvironment() : env;

        // Start a new conversation — use ConversationService directly to avoid
        // the JAX-RS layer which converts exceptions to HTTP responses that are
        // hard to inspect programmatically.
        var initialContext = new HashMap<String, ai.labs.eddi.engine.model.Context>(deployment.getInitialContext());
        var convResult = conversationService.startConversation(usedEnv, agentId, userId, initialContext);
        String conversationId = convResult.conversationId();

        // Store the user conversation mapping
        var userConversation = new UserConversation(intent, userId, usedEnv, agentId, conversationId);
        userConversationStore.createUserConversation(userConversation);

        return userConversation;
    }

    /**
     * Send a message to a Agent synchronously and wait for the response. Bridges
     * the async callback pattern to a blocking call.
     * <p>
     * Finding H7: a busy-skip ({@code onSkipped}, e.g. IN_PROGRESS) must NOT be
     * reported as a fresh agent response — the default onSkipped→onComplete would
     * return the PREVIOUS turn's output as if it answered this message. On a skip
     * we surface a {@link ConversationSkippedException} carrying the persisted
     * state so callers can report "busy — retry" / "not active" instead of a stale
     * reply.
     */
    private SimpleConversationMemorySnapshot sendMessageAndWait(String conversationId, String message) throws Exception {

        var inputData = new InputData();
        inputData.setInput(message);
        inputData.setContext(Map.of("mcp", new Context(Context.ContextType.string, "true")));

        var responseFuture = new CompletableFuture<SimpleConversationMemorySnapshot>();

        conversationService.say(conversationId, false, true, Collections.emptyList(), inputData, false,
                new IConversationService.ConversationResponseHandler() {
                    @Override
                    public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                        if (snapshot != null) {
                            responseFuture.complete(snapshot);
                        } else {
                            responseFuture.completeExceptionally(new RuntimeException("Agent returned null response"));
                        }
                    }

                    @Override
                    public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                        ConversationState state = snapshot != null ? snapshot.getConversationState() : null;
                        responseFuture.completeExceptionally(new ConversationSkippedException(state));
                    }
                });

        try {
            return responseFuture.get(CONVERSATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            // Unwrap the skip signal so callers can catch it directly.
            if (e.getCause() instanceof ConversationSkippedException cse) {
                throw cse;
            }
            throw e;
        }
    }

    /**
     * Signals that a queued {@code say} was DROPPED without processing the input
     * (paused/busy/ended by the time the turn ran). {@code state} is the persisted
     * conversation state at skip time (may be {@code null}). Distinguishes a
     * dropped turn from a real response so a stale previous-turn reply is never
     * returned.
     */
    private static final class ConversationSkippedException extends RuntimeException {
        private final ConversationState state;

        ConversationSkippedException(ConversationState state) {
            super("Conversation turn was skipped (state=" + state + ")");
            this.state = state;
        }

        ConversationState state() {
            return state;
        }
    }

    /**
     * Build a "turn skipped" result for a busy/paused/not-active conversation
     * (finding H7). AWAITING_HUMAN is handled by the caller's pause branch; here we
     * report busy (IN_PROGRESS) vs no-longer-active (ENDED/EXECUTION_INTERRUPTED).
     */
    private String skippedResultJson(String conversationId, ConversationState state) {
        var result = new LinkedHashMap<String, Object>();
        boolean notActive = state == ConversationState.ENDED || state == ConversationState.EXECUTION_INTERRUPTED;
        result.put("status", notActive ? "CONVERSATION_NOT_ACTIVE" : "BUSY");
        result.put("conversationId", conversationId);
        if (state != null) {
            result.put("conversationState", state.name());
        }
        result.put("message", notActive
                ? "Conversation " + conversationId + " is no longer active (state: " + state
                        + "); your message was not processed."
                : "Conversation " + conversationId + " is processing another turn; your message was "
                        + "not processed — retry shortly.");
        try {
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            return errorJson("Conversation " + conversationId + " could not process the message right now");
        }
    }

    /**
     * Build an AI-agent-friendly response from a conversation snapshot. Extracts
     * top-level fields: agentResponse (text), quickReplies, actions,
     * conversationState — so AI agents don't need to dig into the raw snapshot.
     */
    private LinkedHashMap<String, Object> buildConversationResponse(SimpleConversationMemorySnapshot snapshot, String conversationId) {

        var result = new LinkedHashMap<String, Object>();

        // Extract from the LAST conversationOutput (the current step's output)
        var outputs = snapshot.getConversationOutputs();
        if (outputs != null && !outputs.isEmpty()) {
            var lastOutput = outputs.get(outputs.size() - 1);

            // Agent response text — extract text strings from output items
            var outputItems = lastOutput.get("output");
            if (outputItems instanceof List<?> items) {
                var texts = new java.util.ArrayList<String>();
                for (var item : items) {
                    if (item instanceof Map<?, ?> map && map.containsKey("text")) {
                        texts.add(String.valueOf(map.get("text")));
                    }
                }
                if (!texts.isEmpty()) {
                    result.put("agentResponse", String.join(" ", texts));
                    if (texts.size() > 1) {
                        result.put("agentResponseParts", texts);
                    }
                }
            }

            // QuickReplies — extract value strings for easy AI consumption
            var quickReplies = lastOutput.get("quickReplies");
            if (quickReplies instanceof List<?> qrList && !qrList.isEmpty()) {
                var qrValues = new java.util.ArrayList<String>();
                for (var qr : qrList) {
                    if (qr instanceof Map<?, ?> map && map.containsKey("value")) {
                        qrValues.add(String.valueOf(map.get("value")));
                    }
                }
                if (!qrValues.isEmpty()) {
                    result.put("quickReplies", qrValues);
                }
            }

            // Actions
            var actions = lastOutput.get("actions");
            if (actions != null) {
                result.put("actions", actions);
            }
        }

        // Conversation state
        if (snapshot.getConversationState() != null) {
            result.put("conversationState", snapshot.getConversationState().name());
        }

        // Full snapshot for detailed access
        result.put("response", snapshot);

        return result;
    }
}
