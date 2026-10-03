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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Refuses, at the moment an <em>approved</em> tool call is about to execute,
 * the two writes with which an agent could take its own approval gate away. The
 * server-side counterpart of the Manager's {@code self-guard.ts} and
 * {@code gate-guard.ts}.
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
 * <li><b>An LLM configuration write that carries {@code toolApprovals}.</b> A
 * task-level {@code toolApprovals} is combined with the agent gate (strictly by
 * default, wholesale under {@code eddi.hitl.tool.task-approvals.mode=replace}),
 * and an agent should not be the one writing it: changing what gates an agent
 * is a human's edit, made in the Manager, not an approval click. A body that
 * cannot be parsed is refused too — "could not see it" is not "it is not
 * there".</li>
 * </ol>
 * Both are substring tests on the decoded, lower-cased URI, deliberately loose
 * in the same direction as the Manager's: a false positive costs one refused
 * approval, a false negative costs the gate.
 */
public final class SelfUngatingGuard {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String LLM_STORE_PATH = "/llmstore/llms";
    private static final String TOOL_APPROVALS_KEY = "toolApprovals";

    public static final String OWN_AGENT_REASON = "an agent may not modify, deploy or start itself";
    public static final String GATE_WRITE_REASON = "an agent may not write a tool-approval gate (toolApprovals) into an LLM configuration";
    public static final String UNVERIFIABLE_REASON = "the LLM configuration body could not be verified to carry no tool-approval gate";

    private SelfUngatingGuard() {
    }

    /**
     * Checks a resolved request.
     *
     * @return null when it may run, otherwise the reason it may not
     */
    public static String refusal(String method, String uri, String body, String actingAgentId) {
        if (method != null && READ_METHODS.contains(method.trim().toUpperCase(Locale.ROOT))) {
            return null;
        }
        String decodedUri = decodeLower(uri);
        if (containsId(decodedUri, actingAgentId)) {
            return OWN_AGENT_REASON;
        }
        if (decodedUri.contains(LLM_STORE_PATH)) {
            if (body == null || body.isBlank()) {
                return null;
            }
            try {
                JsonNode tree = MAPPER.readTree(body);
                return containsKey(tree, TOOL_APPROVALS_KEY) ? GATE_WRITE_REASON : null;
            } catch (JsonProcessingException e) {
                return UNVERIFIABLE_REASON;
            }
        }
        return null;
    }

    /**
     * The coarser test for a call whose request could not be resolved (a non-http
     * tool, or an http tool whose resolution failed): the raw arguments, without a
     * method to tell a read from a write. Errs toward refusing, like the resolved
     * test.
     */
    public static String refusalFromArguments(String rawArguments, String actingAgentId) {
        String decoded = decodeLower(rawArguments);
        if (containsId(decoded, actingAgentId)) {
            return OWN_AGENT_REASON;
        }
        if (decoded.contains("llmstore") && decoded.contains(TOOL_APPROVALS_KEY.toLowerCase(Locale.ROOT))) {
            return GATE_WRITE_REASON;
        }
        return null;
    }

    private static boolean containsId(String haystack, String id) {
        return id != null && !id.isBlank() && haystack.contains(id.trim().toLowerCase(Locale.ROOT));
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
