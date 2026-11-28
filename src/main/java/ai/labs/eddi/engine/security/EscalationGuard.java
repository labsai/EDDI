/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

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
 * Whether a turn is tainted is {@link TurnOrigin#currentIsEmail()}; outside a
 * tainted turn nothing here refuses anything.
 * <p>
 * <b>Extending it:</b> a new control tool goes into {@link #GUARDED_TOOLS}, a
 * new control endpoint into {@link #GUARDED_PATHS}. Names of tools that do not
 * exist yet are listed on purpose, so the tool cannot ship unguarded.
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
            "resume_conversation", "approve_group_phase", "submit_group_human_input");

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
            List.of("administration", "spend-control-changes", "*", "approve", "**"));

    /** Methods that cannot change anything; never refused. */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /** Separators an MCP proxy or a tool namespace puts in front of a name. */
    private static final String NAMESPACE_SEPARATORS = "_.:/-";

    /** The refusal a tainted turn gets for a guarded tool or endpoint. */
    public static final String REFUSAL = "Refused: this conversation was started by an email, and a turn that an email started may not "
            + "change channel integrations or their sender lists, nor decide approvals. An administrator can do this in the Manager.";

    private EscalationGuard() {
    }

    /**
     * The refusal for calling {@code toolName} in the turn on this thread, if any.
     */
    public static Optional<String> refuseTool(String toolName) {
        return TurnOrigin.currentIsEmail() && isGuardedTool(toolName) ? Optional.of(REFUSAL) : Optional.empty();
    }

    /** The refusal for sending this request in the turn on this thread, if any. */
    public static Optional<String> refuseRequest(String method, URI uri) {
        return TurnOrigin.currentIsEmail() && isGuardedRequest(method, uri) ? Optional.of(REFUSAL) : Optional.empty();
    }

    /** Whether {@code toolName} is, or ends in a namespaced, guarded tool name. */
    public static boolean isGuardedTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String name = toolName.trim().toLowerCase(Locale.ROOT);
        if (GUARDED_TOOLS.contains(name)) {
            return true;
        }
        for (String guarded : GUARDED_TOOLS) {
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
        if (!isWrite(method)) {
            return false;
        }
        List<String> segments = normalisedSegments(rawPath);
        for (List<String> pattern : GUARDED_PATHS) {
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
