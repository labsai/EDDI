/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.apicalls.impl.RequestRedactor;
import ai.labs.eddi.modules.apicalls.impl.ResolvedRequest;
import ai.labs.eddi.modules.llm.tools.spi.ToolRequestResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Map;

/**
 * Builds the "what would this send" preview for an MCP or A2A tool call.
 * <p>
 * Both were previously unpinnable: {@code McpToolsProvider} handed the registry
 * an empty resolver map and {@code A2AToolsProvider} handed it none, so a gated
 * call of either kind showed its approver a tool name and
 * {@code argumentsRedacted} — no target, no fingerprint — and the pre-execution
 * re-check had nothing to compare against. An approver cannot evaluate "call
 * {@code delete_issue}" without knowing <em>which server</em> it is about to be
 * sent to, and a request that is not pinned can be changed between approval and
 * execution without anything noticing.
 * <h3>Why the credential is excluded from the fingerprint</h3> The header is
 * passed in already redacted, which is what {@link ResolvedRequest#of} expects.
 * That is not merely privacy: a connection-backed credential legitimately
 * differs between approval time and execution time (a token refresh in between
 * is normal), so hashing its live value would make every approval of a
 * credentialed call fail its own re-check. The fingerprint covers the target,
 * the method, the tool and its arguments — everything that determines what the
 * call DOES.
 * <h3>Why the body is a preview, not the wire format</h3> The real JSON-RPC
 * envelopes carry a fresh {@code id} per call, and A2A generates two UUIDs.
 * Hashing those would make every fingerprint unique and the re-check
 * meaningless. The preview keeps exactly the fields that decide the outcome and
 * drops the ones that are different by design.
 */
final class RemoteToolRequestResolvers {

    /**
     * Builds the preview, and so decides the structure a human approver reads.
     * <p>
     * Configured to reject trailing tokens: without that, Jackson reads
     * {@code {"a":1} "and the rest"} as the object alone and silently drops what
     * followed, which is the same forgery in a quieter form.
     */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private RemoteToolRequestResolvers() {
    }

    /** Resolver for one MCP tool on one server. */
    static ToolRequestResolver forMcp(String serverUrl, String toolName, boolean hasCredential) {
        return toolRequest -> {
            ObjectNode params = JSON.createObjectNode();
            params.put("name", toolName == null ? "" : toolName);
            params.set("arguments", argumentsNode(toolRequest.arguments()));
            return ResolvedRequest.of("POST", serverUrl, Map.of(), credentialHeader(hasCredential),
                    jsonRpcPreview("tools/call", params), true);
        };
    }

    /** Resolver for one A2A skill on one peer. */
    static ToolRequestResolver forA2A(String agentUrl, boolean hasCredential) {
        return toolRequest -> ResolvedRequest.of("POST", agentUrl, Map.of(), credentialHeader(hasCredential),
                jsonRpcPreview("tasks/send", argumentsNode(toolRequest.arguments())), true);
    }

    /**
     * A redacted {@code Authorization} entry when the config carries a credential,
     * so an approver can see that the call is authenticated without seeing what
     * with — and so that adding or removing a credential DOES move the fingerprint,
     * because that changes who the call runs as.
     */
    private static Map<String, String> credentialHeader(boolean hasCredential) {
        return hasCredential ? Map.of("Authorization", RequestRedactor.REDACTED) : Map.of();
    }

    private static String jsonRpcPreview(String method, JsonNode params) {
        ObjectNode preview = JSON.createObjectNode();
        preview.put("method", method);
        preview.set("params", params);
        return preview.toString();
    }

    /**
     * The model's arguments as a JSON value: parsed when they are one, and quoted
     * as a plain string when they are not.
     * <p>
     * The arguments are MODEL-PRODUCED text. Concatenated into the envelope they
     * were free to close the object they sat in and open fields of their own, so a
     * crafted argument could write a preview that named a different tool, a
     * different method or an extra parameter — and the human reading that preview
     * is being asked to approve exactly those. Parsing first means a well-formed
     * argument object keeps its structure and anything else becomes one string
     * value that cannot escape its quotes.
     * <p>
     * Not defaulted to {@code {}}: a call with no arguments and a call whose
     * arguments failed to arrive are different situations, and collapsing them
     * would give them one fingerprint.
     */
    private static JsonNode argumentsNode(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return JSON.readTree(arguments);
        } catch (JsonProcessingException notJson) {
            return TextNode.valueOf(arguments);
        }
    }
}
