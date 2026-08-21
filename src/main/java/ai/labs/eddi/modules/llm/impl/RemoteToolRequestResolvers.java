/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.apicalls.impl.RequestRedactor;
import ai.labs.eddi.modules.apicalls.impl.ResolvedRequest;
import ai.labs.eddi.modules.llm.tools.spi.ToolRequestResolver;
import com.fasterxml.jackson.core.io.JsonStringEncoder;

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
 * credentialled call fail its own re-check. The fingerprint covers the target,
 * the method, the tool and its arguments — everything that determines what the
 * call DOES.
 * <h3>Why the body is a preview, not the wire format</h3> The real JSON-RPC
 * envelopes carry a fresh {@code id} per call, and A2A generates two UUIDs.
 * Hashing those would make every fingerprint unique and the re-check
 * meaningless. The preview keeps exactly the fields that decide the outcome and
 * drops the ones that are different by design.
 */
final class RemoteToolRequestResolvers {

    private RemoteToolRequestResolvers() {
    }

    /** Resolver for one MCP tool on one server. */
    static ToolRequestResolver forMcp(String serverUrl, String toolName, boolean hasCredential) {
        return toolRequest -> ResolvedRequest.of("POST", serverUrl, Map.of(), credentialHeader(hasCredential),
                jsonRpcPreview("tools/call",
                        "{\"name\":\"" + escape(toolName) + "\",\"arguments\":" + argumentsOrNull(toolRequest.arguments()) + "}"),
                true);
    }

    /** Resolver for one A2A skill on one peer. */
    static ToolRequestResolver forA2A(String agentUrl, boolean hasCredential) {
        return toolRequest -> ResolvedRequest.of("POST", agentUrl, Map.of(), credentialHeader(hasCredential),
                jsonRpcPreview("tasks/send", argumentsOrNull(toolRequest.arguments())), true);
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

    private static String jsonRpcPreview(String method, String params) {
        return "{\"method\":\"" + escape(method) + "\",\"params\":" + params + "}";
    }

    /**
     * The model's arguments verbatim when it produced any, {@code null} otherwise.
     * <p>
     * Not defaulted to {@code {}}: a call with no arguments and a call whose
     * arguments failed to arrive are different situations, and collapsing them
     * would give them one fingerprint.
     */
    private static String argumentsOrNull(String arguments) {
        return arguments == null || arguments.isBlank() ? "null" : arguments;
    }

    /** JSON string escaping for the two EDDI-authored literals above. */
    private static String escape(String value) {
        return value == null ? "" : new String(JsonStringEncoder.getInstance().quoteAsString(value));
    }
}
