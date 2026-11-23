/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.hitl.tools.ApprovedCallScope;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The escalation guard of the email channel (email plan v3 §5.3): a turn that a
 * mail started must never widen who can make EDDI act, nor decide its own
 * approvals.
 * <p>
 * This is the authoritative check, at runtime. It is asked in
 * {@code ToolExecutionService} for every tool call (LLM tools of every source,
 * the HITL resume loop, {@code mcpcalls}), and in the two HTTP paths —
 * {@code ApiCallExecutor} for {@code httpcalls} and {@code OutboundHttp} for
 * every redirect hop — for a request to one of EDDI's own control endpoints.
 * The save-time check in {@code EmailAgentToolsInspector} is best effort on top
 * of it, never instead of it.
 * <p>
 * The same lists are asked on the receiving side, for a request that carries
 * the taint in {@link TurnOrigin#HEADER}: {@code TaintedRequestFilter} for REST
 * and {@code McpErrorResultInterceptor} for EDDI's MCP tools. Starting or
 * continuing a conversation, a schedule or a group discussion is not refused:
 * what a tainted turn starts that way is tainted itself ({@link TurnOrigin}).
 * <p>
 * Whether a turn is tainted is {@link TurnOrigin#currentIsEmail()}; outside a
 * tainted turn nothing here refuses anything.
 * <p>
 * Two rules, two pairs of lists:
 * <ul>
 * <li>{@link #GUARDED_TOOLS} and {@link #GUARDED_PATHS} — channel integrations
 * and senders, approvals, shares, secrets, connections: <b>never</b>, whoever
 * approves;</li>
 * <li>{@link #CONFIG_TOOLS} and {@link #CONFIG_PATHS} — agent configuration and
 * deployment: only when a person approved that very call through the HITL
 * tool-approval gate. In the engine that is {@link ApprovedCallScope}, bound
 * around exactly the approved execution; across an HTTP hop to EDDI's own API
 * the sender says so in {@link TurnOrigin#APPROVED_HEADER}, which it sets
 * itself and strips from every other request a tainted turn sends.</li>
 * </ul>
 * <b>Extending it:</b> a new control tool goes into {@link #GUARDED_TOOLS}, a
 * new control endpoint into {@link #GUARDED_PATHS}, a new configuration store
 * into {@link #CONFIG_PATHS}. Names of tools that do not exist yet are listed
 * on purpose, so the tool cannot ship unguarded.
 */
public final class EscalationGuard {

    /**
     * Tools a tainted turn may never call: everything that changes a channel
     * integration or its senders, and everything that decides an approval. Matched
     * by name, also behind a namespace prefix such as {@code eddi_} or
     * {@code eddi.} that an MCP proxy may add.
     */
    public static final Set<String> GUARDED_TOOLS = Set.of(
            // channel integrations (McpAdminTools)
            "create_channel_integration", "update_channel_integration", "delete_channel_integration",
            // email sender lists (C1)
            "update_email_senders",
            // later phases of the email control plan, guarded before they exist
            "update_email_roles", "update_email_send_out", "decide_held_email",
            // HITL decisions (McpHitlTools)
            "resume_conversation", "approve_group_phase", "submit_group_human_input",
            // team handoff decisions (McpTeamHandoffTools): "a human decision"
            "accept_team_handoff", "decline_team_handoff");

    /**
     * EDDI's own control endpoints a tainted turn may never write to. Segments are
     * literal, {@code *} is one segment, a trailing {@code **} any number. Matched
     * anywhere in the request path, so a {@code quarkus.http.root-path} or a proxy
     * prefix does not hide one, and only for methods that can change something.
     */
    public static final List<List<String>> GUARDED_PATHS = List.of(
            List.of("channelstore", "channels", "**"),
            List.of("agents", "*", "resume", "**"),
            List.of("groups", "*", "conversations", "*", "approve", "**"),
            List.of("groups", "*", "conversations", "*", "human-input", "**"),
            // Oversight: sample verdicts, earned autonomy, the analytics rebuild and
            // every spend-control decision — the paths AgentOriginatedRequestFilter
            // keeps from any agent's httpcall.
            List.of("hitl", "samples", "**"),
            List.of("hitl", "autonomy", "**"),
            List.of("hitl", "analytics", "rebuild", "**"),
            List.of("administration", "spend-control-changes", "**"),
            // Team handoff decisions.
            List.of("groupstore", "groups", "*", "workspace", "handoffs", "*", "accept", "**"),
            List.of("groupstore", "groups", "*", "workspace", "handoffs", "*", "decline", "**"),
            // Who may use a resource: shares, share requests, visibility, space, owner.
            List.of("descriptorstore", "descriptors", "*", "shares", "**"),
            // Secrets: values, grants to agents, rotation, reset — tenant and space.
            List.of("secretstore", "**"),
            List.of("spacestore", "secrets", "**"),
            // Connections: their definitions and settings, and a user's grant of one.
            List.of("connectionstore", "**"),
            List.of("connections", "*", "authorize", "**"),
            List.of("connections", "*", "grant", "**"),
            // Workspace settings (who may do what in a workspace).
            List.of("workspaces", "settings", "**"));

    /**
     * Tools that write agent configuration or change what is deployed: a tainted
     * turn may call them only when a person approved that very call through the
     * HITL tool-approval gate ({@code ApprovedCallScope}) — see
     * {@link #refuseTool(String, boolean)}. Kept apart from {@link #GUARDED_TOOLS},
     * which no approval unlocks and which the save-time check in
     * {@code EmailAgentToolsInspector} reads.
     * <p>
     * Without this, a mail could rewrite the prompt, the http calls or the rules an
     * untainted conversation later runs, and so get its text run with every tool
     * this guard refuses the mail's own conversation.
     */
    public static final Set<String> CONFIG_TOOLS = Set.of(
            // agents, their documents and their deployment (McpAdminTools, McpSetupTools)
            "create_agent", "update_agent", "delete_agent", "create_api_agent", "setup_agent", "apply_agent_changes",
            "create_resource", "update_resource", "delete_resource", "deploy_agent", "undeploy_agent",
            // triggers: which agent a channel or intent starts
            "create_agent_trigger", "update_agent_trigger", "delete_agent_trigger",
            // group configurations (McpGroupTools)
            "create_group", "update_group", "delete_group", "create_group_from_template",
            // dynamic agents (CreateSubAgentTool, TeardownAgentTool): a created agent is
            // deployed, and a retained one outlives the discussion
            "createsubagent", "retainagent");

    /**
     * Configuration endpoints, under the same rule as {@link #CONFIG_TOOLS}: a
     * tainted turn's write is refused unless a person approved the call. The syntax
     * is that of {@link #GUARDED_PATHS}, plus {@code $}: the path ends there. The
     * v5 store names are listed too, because the sending side sees the path before
     * {@code LegacyPathRewriteFilter} rewrites it.
     * <p>
     * A team's workspace (backlog, handoffs, lessons) is not configuration: what a
     * tainted turn writes there is tainted itself ({@code SharedTaskList}), not
     * refused.
     */
    public static final List<List<String>> CONFIG_PATHS = List.of(
            List.of("agentstore", "**"), List.of("botstore", "**"),
            List.of("workflowstore", "**"), List.of("packagestore", "**"),
            List.of("llmstore", "**"), List.of("langchainstore", "**"),
            List.of("apicallstore", "**"), List.of("httpcallsstore", "**"),
            List.of("rulestore", "**"), List.of("behaviorstore", "**"),
            List.of("dictionarystore", "**"), List.of("regulardictionarystore", "**"),
            List.of("agenttriggerstore", "**"), List.of("bottriggerstore", "**"),
            List.of("mcpcallsstore", "**"),
            List.of("ragstore", "**"),
            List.of("outputstore", "**"),
            List.of("propertysetterstore", "**"),
            List.of("snippetstore", "**"),
            List.of("variablestore", "**"),
            List.of("spacestore", "variables", "**"),
            List.of("parserstore", "**"),
            List.of("extensionstore", "**"),
            List.of("deploymentstore", "**"),
            // group configurations and templates, not the workspace below them
            List.of("groupstore", "groups", "$"),
            List.of("groupstore", "groups", "*", "$"),
            List.of("groupstore", "templates", "**"),
            // deployment and the setup wizard
            List.of("administration", "*", "deploy", "**"),
            List.of("administration", "*", "undeploy", "**"),
            List.of("administration", "agents", "**"),
            // import and sync write every kind of document at once
            List.of("backup", "import", "**"));

    /** Methods that cannot change anything; never refused. */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /** Separators an MCP proxy or a tool namespace puts in front of a name. */
    private static final String NAMESPACE_SEPARATORS = "_.:/-";

    /** The refusal a tainted turn gets for a guarded tool or endpoint. */
    public static final String REFUSAL = "Refused: this conversation was started by an email, and a turn that an email started may not "
            + "change channel integrations or their sender lists, nor decide approvals. An administrator can do this in the Manager.";

    /**
     * The refusal a tainted turn gets for an unapproved configuration write
     * ({@link #CONFIG_TOOLS}, {@link #CONFIG_PATHS}).
     */
    public static final String CONFIG_REFUSAL = "Refused: this conversation was started by an email, and a turn that an email started may "
            + "not change agent configuration, prompts, triggers or deployments unless a person approves that call. Gate the tool with a "
            + "tool approval (hitlConfig.toolApprovals), or make the change in the Manager.";

    private EscalationGuard() {
    }

    /**
     * The refusal for calling {@code toolName} in the turn on this thread, if any.
     * A configuration tool is allowed when the call is the approved execution of a
     * gated call ({@link ApprovedCallScope#isActive()}).
     */
    public static Optional<String> refuseTool(String toolName) {
        return refuseTool(toolName, ApprovedCallScope.isActive());
    }

    /**
     * As {@link #refuseTool(String)}, with whether a person approved this very call
     * given: {@code approved} unlocks {@link #CONFIG_TOOLS}, never
     * {@link #GUARDED_TOOLS}.
     */
    public static Optional<String> refuseTool(String toolName, boolean approved) {
        if (!TurnOrigin.currentIsEmail()) {
            return Optional.empty();
        }
        if (isGuardedTool(toolName)) {
            return Optional.of(REFUSAL);
        }
        return !approved && isConfigTool(toolName) ? Optional.of(CONFIG_REFUSAL) : Optional.empty();
    }

    /**
     * The refusal for sending this request in the turn on this thread, if any. A
     * configuration write is allowed during the approved execution of a gated call.
     */
    public static Optional<String> refuseRequest(String method, URI uri) {
        return refuseRequest(method, uri, ApprovedCallScope.isActive());
    }

    /**
     * As {@link #refuseRequest(String, URI)}, with whether a person approved this
     * very call given.
     */
    public static Optional<String> refuseRequest(String method, URI uri, boolean approved) {
        if (!TurnOrigin.currentIsEmail()) {
            return Optional.empty();
        }
        if (isGuardedRequest(method, uri)) {
            return Optional.of(REFUSAL);
        }
        return !approved && isConfigRequest(method, uri == null ? null : uri.getRawPath())
                ? Optional.of(CONFIG_REFUSAL)
                : Optional.empty();
    }

    /**
     * The refusal for a request that reached EDDI with this method, path and taint,
     * if any: {@code tainted} is what {@link TurnOrigin#HEADER} said, and
     * {@code approved} what {@link TurnOrigin#APPROVED_HEADER} said — which only
     * counts on a tainted request.
     */
    public static Optional<String> refuseInbound(String method, String rawPath, boolean tainted, boolean approved) {
        if (!tainted) {
            return Optional.empty();
        }
        if (isGuardedRequest(method, rawPath)) {
            return Optional.of(REFUSAL);
        }
        return !approved && isConfigRequest(method, rawPath) ? Optional.of(CONFIG_REFUSAL) : Optional.empty();
    }

    /** Whether {@code toolName} is, or ends in a namespaced, guarded tool name. */
    public static boolean isGuardedTool(String toolName) {
        return matchesToolName(toolName, GUARDED_TOOLS);
    }

    /**
     * Whether {@code toolName} is, or ends in a namespaced, configuration tool name
     * ({@link #CONFIG_TOOLS}).
     */
    public static boolean isConfigTool(String toolName) {
        return matchesToolName(toolName, CONFIG_TOOLS);
    }

    /**
     * Whether a request with this method to this raw path writes configuration
     * ({@link #CONFIG_PATHS}).
     */
    public static boolean isConfigRequest(String method, String rawPath) {
        return isWrite(method) && matchesAny(CONFIG_PATHS, rawPath);
    }

    private static boolean matchesToolName(String toolName, Set<String> names) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String name = toolName.trim().toLowerCase(Locale.ROOT);
        if (names.contains(name)) {
            return true;
        }
        for (String guarded : names) {
            if (name.endsWith(guarded) && name.length() > guarded.length()
                    && NAMESPACE_SEPARATORS.indexOf(name.charAt(name.length() - guarded.length() - 1)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a request with this method can change something: every method but
     * {@code GET}, {@code HEAD}, {@code OPTIONS} and {@code TRACE}, a missing one
     * included.
     */
    public static boolean isWrite(String method) {
        return method == null || !SAFE_METHODS.contains(method.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Whether a request with this method to this URI writes to a guarded endpoint.
     * A missing method counts as a write.
     */
    public static boolean isGuardedRequest(String method, URI uri) {
        if (uri == null) {
            return false;
        }
        return isGuardedRequest(method, uri.getRawPath());
    }

    /** As {@link #isGuardedRequest(String, URI)}, for a raw request path. */
    public static boolean isGuardedRequest(String method, String rawPath) {
        return isWrite(method) && matchesAny(GUARDED_PATHS, rawPath);
    }

    private static boolean matchesAny(List<List<String>> patterns, String rawPath) {
        List<String> segments = normalisedSegments(rawPath);
        for (List<String> pattern : patterns) {
            for (int start = 0; start < segments.size(); start++) {
                if (matches(pattern, segments, start)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matches(List<String> pattern, List<String> segments, int start) {
        int position = start;
        for (String part : pattern) {
            if ("**".equals(part)) {
                return true;
            }
            if ("$".equals(part)) {
                return position == segments.size();
            }
            if (position >= segments.size()) {
                return false;
            }
            if (!"*".equals(part) && !part.equals(segments.get(position))) {
                return false;
            }
            position++;
        }
        return true;
    }

    /**
     * The path's segments as a server would route them: percent-decoded until
     * stable, backslashes read as slashes, matrix parameters ({@code ;x=y}) and
     * empty or {@code .} segments dropped, {@code ..} applied, lower case.
     */
    static List<String> normalisedSegments(String rawPath) {
        List<String> segments = new ArrayList<>();
        if (rawPath == null || rawPath.isEmpty()) {
            return segments;
        }
        String path = rawPath;
        for (int i = 0; i < 3 && path.indexOf('%') >= 0; i++) {
            String decoded;
            try {
                decoded = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                break;
            }
            if (decoded.equals(path)) {
                break;
            }
            path = decoded;
        }
        for (String raw : path.replace('\\', '/').split("/")) {
            int matrix = raw.indexOf(';');
            String segment = (matrix >= 0 ? raw.substring(0, matrix) : raw).trim().toLowerCase(Locale.ROOT);
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) {
                    segments.remove(segments.size() - 1);
                }
                continue;
            }
            segments.add(segment);
        }
        return segments;
    }
}
