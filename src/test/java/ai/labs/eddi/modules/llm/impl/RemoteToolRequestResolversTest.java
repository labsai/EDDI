/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP and A2A calls used to be unpinnable — an approver saw a tool name and
 * nothing else, and the pre-execution re-check had nothing to compare against.
 * These tests pin both halves of the fix: the preview must name the target, and
 * the fingerprint must move exactly when the call's effect would.
 */
class RemoteToolRequestResolversTest {

    private static final String SERVER = "https://mcp.example.com/rpc";

    private static ToolExecutionRequest call(String name, String arguments) {
        return ToolExecutionRequest.builder().id("c1").name(name).arguments(arguments).build();
    }

    @Test
    @DisplayName("an MCP preview names the server, the method and the tool")
    void mcpPreviewNamesTheTarget() throws Exception {
        var resolved = RemoteToolRequestResolvers.forMcp(SERVER, "delete_issue", false).resolve(call("delete_issue", "{\"key\":\"ENG-1\"}"));

        assertEquals("POST", resolved.method());
        assertEquals(SERVER, resolved.uri(), "an approver who cannot see which server is being called cannot approve it");
        assertTrue(resolved.body().contains("tools/call"));
        assertTrue(resolved.body().contains("delete_issue"));
        assertTrue(resolved.body().contains("ENG-1"));
        assertNotNull(resolved.fingerprint(), "an unpinned request can be changed between approval and execution");
    }

    @Test
    @DisplayName("a credential is shown as present and never as itself")
    void credentialIsRedactedNotOmitted() throws Exception {
        var resolved = RemoteToolRequestResolvers.forMcp(SERVER, "list_issues", true).resolve(call("list_issues", "{}"));

        assertEquals("<REDACTED>", resolved.headers().get("authorization"), resolved.headers().toString());
    }

    @Test
    @DisplayName("a credential's live VALUE does not enter the fingerprint")
    void tokenRefreshDoesNotBreakTheRecheck() throws Exception {
        // The resolver is constructed from a boolean, not a token, so there is no
        // value that could vary. Asserted explicitly because the opposite design —
        // hashing the resolved credential — makes every approval of a
        // connection-backed call fail its own re-check after a routine refresh.
        var first = RemoteToolRequestResolvers.forMcp(SERVER, "list_issues", true).resolve(call("list_issues", "{}"));
        var second = RemoteToolRequestResolvers.forMcp(SERVER, "list_issues", true).resolve(call("list_issues", "{}"));

        assertEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    @DisplayName("changing the arguments moves the fingerprint")
    void argumentsAreFingerprinted() throws Exception {
        var resolver = RemoteToolRequestResolvers.forMcp(SERVER, "delete_issue", false);

        assertNotEquals(resolver.resolve(call("delete_issue", "{\"key\":\"ENG-1\"}")).fingerprint(),
                resolver.resolve(call("delete_issue", "{\"key\":\"ENG-2\"}")).fingerprint());
    }

    @Test
    @DisplayName("changing the target server moves the fingerprint")
    void serverIsFingerprinted() throws Exception {
        var call = call("delete_issue", "{}");

        assertNotEquals(RemoteToolRequestResolvers.forMcp(SERVER, "delete_issue", false).resolve(call).fingerprint(),
                RemoteToolRequestResolvers.forMcp("https://evil.example.com/rpc", "delete_issue", false).resolve(call).fingerprint());
    }

    @Test
    @DisplayName("adding a credential moves the fingerprint — it changes who the call runs as")
    void credentialPresenceIsFingerprinted() throws Exception {
        var call = call("delete_issue", "{}");

        assertNotEquals(RemoteToolRequestResolvers.forMcp(SERVER, "delete_issue", false).resolve(call).fingerprint(),
                RemoteToolRequestResolvers.forMcp(SERVER, "delete_issue", true).resolve(call).fingerprint());
    }

    @Test
    @DisplayName("an A2A preview names the peer and is stable across calls")
    void a2aPreviewIsStable() throws Exception {
        var resolver = RemoteToolRequestResolvers.forA2A("https://peer.example.com", false);
        var call = call("peer_lookup", "{\"message\":\"hello\"}");

        var first = resolver.resolve(call);
        var second = resolver.resolve(call);

        assertEquals("https://peer.example.com", first.uri());
        assertTrue(first.body().contains("tasks/send"));
        assertEquals(first.fingerprint(), second.fingerprint(),
                "the real A2A envelope carries two fresh UUIDs; hashing those would make every fingerprint unique "
                        + "and the pre-execution re-check meaningless");
    }

    @Test
    @DisplayName("a credential-shaped argument is redacted in the stored preview")
    void previewBodyIsRedacted() throws Exception {
        var resolved = RemoteToolRequestResolvers.forMcp(SERVER, "store", true)
                .resolve(call("store", "{\"value\":\"sk-ant-abcdefghijklmnopqrstuvwxyz01\"}"));

        assertFalse(resolved.body().contains("abcdefghijklmnopqrstuvwxyz01"),
                "the preview is shown to a human who is routinely not the person whose turn raised the pause: " + resolved.body());
    }
}
