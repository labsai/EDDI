/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refuses, at the moment an <em>approved</em> tool call is about to execute,
 * the writes with which an agent could take its own approval gate away — or
 * another agent's. The server-side counterpart of the Manager's
 * {@code self-guard.ts} and {@code gate-guard.ts}.
 * <p>
 * Those guards only ever governed the Manager's approval surfaces. The same
 * pause can be decided from Slack, through the MCP {@code resume_conversation}
 * tool or with {@code POST .../resume}, and every one of those executes the
 * approved call through {@code ToolLoopResumer} — so that is where the rule has
 * to live to be a rule rather than a UI hint. The Manager guards stay: they
 * tell the approver before a decision is spent on a call the engine will
 * refuse.
 * <ol>
 * <li><b>Writes to the acting agent itself.</b> A non-read request whose URI
 * names the agent the conversation is running — {@code PUT
 * /agentstore/agents/{own}/updateResourceUri} is the hinge of the loop
 * {@code self-guard.ts} describes (repoint own workflow at a permissive LLM
 * document, update the agent to it, redeploy). Deploy, undeploy and starting a
 * conversation with itself are refused by the same test, as in the
 * Manager.</li>
 * <li><b>Writes to the resources the acting agent runs on.</b> The agent id
 * alone left the loop's other half open: the workflow the agent lists and the
 * extension documents that workflow references (its LLM configuration, rules,
 * api calls…) carry their own ids, and a write to any of them rewrites what the
 * agent does without ever naming the agent (security review 2026-10-15, finding
 * L13). The resumer resolves those ids at gate time and passes them in a
 * {@link Protection}; every one of them is refused like the agent id.</li>
 * <li><b>An LLM configuration write that carries {@code toolApprovals}.</b> A
 * task-level {@code toolApprovals} is combined with the agent gate (strictly by
 * default, wholesale under {@code eddi.hitl.tool.task-approvals.mode=replace}),
 * and an agent should not be the one writing it: changing what gates an agent
 * is a human's edit, made in the Manager, not an approval click. A body that
 * cannot be parsed is refused too — "could not see it" is not "it is not
 * there".</li>
 * <li><b>An agent-store write that removes or changes another agent's gate.</b>
 * {@code hitlConfig.toolApprovals} lives on the agent document, so a full
 * {@code PUT} of another agent that drops or alters it — or a {@code PATCH}
 * that names it — ungates that agent exactly as the LLM-store write would
 * ungate a task (L13). With a {@link Protection#storedGates lookup} the body's
 * {@code hitlConfig} is compared against what is stored and refused when it
 * differs; without one, a body that carries {@code hitlConfig} or
 * {@code toolApprovals} at all is refused, because it cannot be told apart from
 * a removal.</li>
 * </ol>
 * The URI tests are substring tests on the decoded, lower-cased URI,
 * deliberately loose in the same direction as the Manager's: a false positive
 * costs one refused approval, a false negative costs the gate.
 */
public final class SelfUngatingGuard {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    /**
     * The LLM-configuration store and the v5 name it is still reachable under —
     * {@code LegacyPathRewriteFilter} rewrites the old one onto the new one before
     * routing, so a test on only the current path is bypassed by spelling the old.
     */
    private static final List<String> LLM_STORE_PATHS = List.of("/llmstore/llms", "/langchainstore/langchains");
    private static final String UUID_ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    /**
     * The agent store and its v5 name, same reason. The id is either shape a
     * backend mints — a 24-hex ObjectId (MongoDB) or a canonical UUID (PostgreSQL);
     * the URI is lower-cased before this runs.
     */
    private static final Pattern AGENT_STORE_TARGET = Pattern
            .compile("/(?:agentstore/agents|botstore/bots)/([0-9a-f]{24}|" + UUID_ID + ")(?:[/?#]|$)");
    /** Every configuration store answers under {@code /<name>store/}. */
    private static final Pattern CONFIG_STORE_PATH = Pattern.compile("/[a-z]+store/");
    private static final String TOOL_APPROVALS_KEY = "toolApprovals";
    private static final String HITL_CONFIG_KEY = "hitlConfig";

    public static final String OWN_AGENT_REASON = "an agent may not modify, deploy or start itself";
    public static final String OWN_RESOURCE_REASON = "an agent may not modify the workflow or configuration resources it runs on";
    public static final String GATE_WRITE_REASON = "an agent may not write a tool-approval gate (toolApprovals) into an LLM configuration";
    public static final String GATE_REMOVAL_REASON = "an agent may not remove or change another agent's tool-approval gate (hitlConfig)";
    public static final String PATCH_OP_REASON = "a configuration patch may only use add, replace, remove and test operations";
    private static final Set<String> PATCH_OPS = Set.of("add", "replace", "remove", "test");
    public static final String UNVERIFIABLE_REASON = "the LLM configuration body could not be verified to carry no tool-approval gate";
    public static final String UNRESOLVED_RESOURCES_REASON = "the acting agent's own resources could not be resolved, so no configuration "
            + "store may be written";

    private SelfUngatingGuard() {
    }

    /**
     * Reads the stored gate of an agent, so an agent-store write can be compared
     * against what it replaces.
     */
    @FunctionalInterface
    public interface StoredGateLookup {
        /**
         * @return the stored agent's {@code hitlConfig} as JSON, {@code null} when the
         *         agent has none
         * @throws Exception
         *             when it cannot be read — the write is then refused as
         *             unverifiable, never admitted
         */
        JsonNode hitlConfigOf(String agentId) throws Exception;
    }

    /**
     * What the gate protects for one approved execution.
     *
     * @param actingAgentId
     *            the agent whose conversation is running; nullable
     * @param ownResourceIds
     *            ids of the workflows the acting agent lists and of every extension
     *            resource those workflows reference; never null
     * @param resourcesUnresolved
     *            true when the ids could not be resolved — the guard then refuses
     *            every write to a configuration store, since it cannot tell the
     *            agent's own from another's
     * @param storedGates
     *            lookup of another agent's stored gate; nullable
     */
    public record Protection(String actingAgentId, Set<String> ownResourceIds, boolean resourcesUnresolved, StoredGateLookup storedGates) {

        public Protection {
            ownResourceIds = ownResourceIds == null ? Set.of() : Set.copyOf(ownResourceIds);
        }

        /** The agent id alone — what the guard knew before L13. */
        public static Protection ofAgent(String actingAgentId) {
            return new Protection(actingAgentId, Set.of(), false, null);
        }
    }

    /**
     * Checks a resolved request against the acting agent's id alone. Kept for
     * callers without a resolved {@link Protection}; the resumer passes one.
     *
     * @return null when it may run, otherwise the reason it may not
     */
    public static String refusal(String method, String uri, String body, String actingAgentId) {
        return refusal(method, uri, body, Protection.ofAgent(actingAgentId));
    }

    /**
     * Checks a resolved request.
     *
     * @return null when it may run, otherwise the reason it may not
     */
    public static String refusal(String method, String uri, String body, Protection protection) {
        if (method != null && READ_METHODS.contains(method.trim().toUpperCase(Locale.ROOT))) {
            return null;
        }
        Protection p = protection != null ? protection : Protection.ofAgent(null);
        String decodedUri = decodeLower(uri);
        if (containsId(decodedUri, p.actingAgentId())) {
            return OWN_AGENT_REASON;
        }
        if (containsAnyId(decodedUri, p.ownResourceIds())) {
            return OWN_RESOURCE_REASON;
        }
        if (p.resourcesUnresolved() && CONFIG_STORE_PATH.matcher(decodedUri).find()) {
            return UNRESOLVED_RESOURCES_REASON;
        }
        if (LLM_STORE_PATHS.stream().anyMatch(decodedUri::contains)) {
            return llmStoreRefusal(method, body);
        }
        Matcher agentTarget = AGENT_STORE_TARGET.matcher(decodedUri);
        if (agentTarget.find()) {
            return agentStoreRefusal(method, body, agentTarget.group(1), p.storedGates());
        }
        return null;
    }

    private static String llmStoreRefusal(String method, String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode tree = MAPPER.readTree(body);
            if (tree.isArray() && isPatch(method)) {
                return patchRefusal(tree, TOOL_APPROVALS_KEY);
            }
            return containsKey(tree, TOOL_APPROVALS_KEY) ? GATE_WRITE_REASON : null;
        } catch (JsonProcessingException e) {
            return UNVERIFIABLE_REASON;
        }
    }

    /**
     * A write to another agent's document (the acting agent's own was refused
     * above). A patch naming the gate is refused outright; a full document is
     * compared against the stored gate when a lookup exists, and refused on any
     * difference — removal included, which is a body with no {@code hitlConfig}
     * where one is stored.
     */
    private static String agentStoreRefusal(String method, String body, String targetAgentId, StoredGateLookup storedGates) {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode tree;
        try {
            tree = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            // Not JSON — the bare resource URI of updateResourceUri, say. A gate is JSON
            // structure, so the text cannot carry one; the names alone are refused, as
            // the raw-argument fallback refuses them.
            String lower = body.toLowerCase(Locale.ROOT);
            return lower.contains(HITL_CONFIG_KEY.toLowerCase(Locale.ROOT)) || lower.contains(TOOL_APPROVALS_KEY.toLowerCase(Locale.ROOT))
                    ? GATE_REMOVAL_REASON
                    : null;
        }
        if (tree.isArray() && isPatch(method)) {
            String named = patchRefusal(tree, HITL_CONFIG_KEY);
            if (named != null) {
                return named.equals(GATE_WRITE_REASON) ? GATE_REMOVAL_REASON : named;
            }
            return patchRefusal(tree, TOOL_APPROVALS_KEY) != null ? GATE_REMOVAL_REASON : null;
        }
        if (!tree.isObject()) {
            // Not an agent document (a resource URI for updateResourceUri, say):
            // nothing in it can carry or drop a gate.
            return containsKey(tree, TOOL_APPROVALS_KEY) || containsKey(tree, HITL_CONFIG_KEY) ? GATE_REMOVAL_REASON : null;
        }
        if (storedGates == null) {
            // No way to tell a faithful copy from a removal: a body that touches the
            // gate at all is a human's edit.
            return containsKey(tree, HITL_CONFIG_KEY) || containsKey(tree, TOOL_APPROVALS_KEY) ? GATE_REMOVAL_REASON : null;
        }
        JsonNode stored;
        try {
            stored = storedGates.hitlConfigOf(targetAgentId);
        } catch (Exception e) {
            return UNVERIFIABLE_REASON;
        }
        JsonNode written = tree.get(HITL_CONFIG_KEY);
        boolean storedHasGate = stored != null && !stored.isNull() && stored.has(TOOL_APPROVALS_KEY) && !stored.get(TOOL_APPROVALS_KEY).isNull();
        boolean writtenHasGate = written != null && !written.isNull() && written.has(TOOL_APPROVALS_KEY)
                && !written.get(TOOL_APPROVALS_KEY).isNull();
        if (!storedHasGate && !writtenHasGate) {
            return null;
        }
        if (storedHasGate && writtenHasGate && stored.get(TOOL_APPROVALS_KEY).equals(written.get(TOOL_APPROVALS_KEY))) {
            return null;
        }
        return GATE_REMOVAL_REASON;
    }

    private static boolean isPatch(String method) {
        return method != null && "PATCH".equals(method.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * An RFC 6902 patch. The gate is out of reach for a patch in three ways the
     * whole-document test cannot see: an operation whose {@code path} or
     * {@code from} NAMES the field (the key is then a string, not a key), a
     * {@code value} that carries it, and {@code copy}/{@code move}, which carry
     * part of the stored document — a gate override included — somewhere the body
     * does not name. Mirrors {@code gate-guard.ts}: the field named anywhere is
     * refused first, then any operation outside add/replace/remove/test.
     */
    private static String patchRefusal(JsonNode operations, String key) {
        boolean unsupported = false;
        String needle = key.toLowerCase(Locale.ROOT);
        for (JsonNode operation : operations) {
            if (!operation.isObject()) {
                return UNVERIFIABLE_REASON;
            }
            for (String pointer : new String[]{"path", "from"}) {
                JsonNode target = operation.get(pointer);
                if (target != null && target.asText("").toLowerCase(Locale.ROOT).contains(needle)) {
                    return GATE_WRITE_REASON;
                }
            }
            if (containsKey(operation.get("value"), key)) {
                return GATE_WRITE_REASON;
            }
            JsonNode op = operation.get("op");
            if (op == null || !op.isTextual() || !PATCH_OPS.contains(op.asText())) {
                unsupported = true;
            }
        }
        return unsupported ? PATCH_OP_REASON : null;
    }

    /**
     * The coarser test for a call whose request could not be resolved (a non-http
     * tool, or an http tool whose resolution failed): the raw arguments, without a
     * method to tell a read from a write. Errs toward refusing, like the resolved
     * test.
     */
    public static String refusalFromArguments(String rawArguments, String actingAgentId) {
        return refusalFromArguments(rawArguments, Protection.ofAgent(actingAgentId));
    }

    /**
     * As {@link #refusalFromArguments(String, String)}, with the full protection.
     */
    public static String refusalFromArguments(String rawArguments, Protection protection) {
        Protection p = protection != null ? protection : Protection.ofAgent(null);
        String decoded = decodeLower(rawArguments);
        if (containsId(decoded, p.actingAgentId())) {
            return OWN_AGENT_REASON;
        }
        if (containsAnyId(decoded, p.ownResourceIds())) {
            return OWN_RESOURCE_REASON;
        }
        if (p.resourcesUnresolved() && CONFIG_STORE_PATH.matcher(decoded).find()) {
            return UNRESOLVED_RESOURCES_REASON;
        }
        // Without a resolved request there is no path to test, and the call may be an
        // MCP tool aimed at this very server (update_resource on an LLM document), so
        // the key alone is enough: no legitimate argument list needs to carry it.
        if (decoded.contains(TOOL_APPROVALS_KEY.toLowerCase(Locale.ROOT))) {
            return GATE_WRITE_REASON;
        }
        if (decoded.contains(HITL_CONFIG_KEY.toLowerCase(Locale.ROOT))) {
            return GATE_REMOVAL_REASON;
        }
        return null;
    }

    private static boolean containsId(String haystack, String id) {
        return id != null && !id.isBlank() && haystack.contains(id.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean containsAnyId(String haystack, Set<String> ids) {
        for (String id : ids) {
            if (containsId(haystack, id)) {
                return true;
            }
        }
        return false;
    }

    private static String decodeLower(String value) {
        if (value == null) {
            return "";
        }
        String decoded = value;
        try {
            decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // A malformed escape is not a reason to stop checking — keep the raw form.
        }
        return decoded.toLowerCase(Locale.ROOT);
    }

    /** Whether {@code key} appears as an object key at any depth. */
    static boolean containsKey(JsonNode node, String key) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (key.equals(field.getKey()) || containsKey(field.getValue(), key)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (containsKey(element, key)) {
                    return true;
                }
            }
        }
        return false;
    }
}
