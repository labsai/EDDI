/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.McpCallToolRequest;
import dev.langchain4j.mcp.protocol.McpClientMessage;
import dev.langchain4j.mcp.protocol.McpClientMethod;
import dev.langchain4j.mcp.protocol.McpListResourcesRequest;
import dev.langchain4j.mcp.protocol.McpListToolsRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The page, item and deadline bounds on paginated MCP listings — the guard
 * against a server that answers every {@code tools/list} page with a fresh
 * cursor.
 */
@DisplayName("BoundedListingMcpTransport — paginated listings are bounded")
class BoundedListingMcpTransportTest {

    private static final BoundedListingMcpTransport.Limits LIMITS = new BoundedListingMcpTransport.Limits(3, 10, Duration.ofSeconds(30));

    private static String page(int tools, String cursor) {
        var sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[");
        for (int i = 0; i < tools; i++) {
            sb.append(i > 0 ? "," : "").append("{\"name\":\"t").append(i).append("\",\"inputSchema\":{\"type\":\"object\"}}");
        }
        sb.append("]");
        if (cursor != null) {
            sb.append(",\"nextCursor\":\"").append(cursor).append('"');
        }
        return sb.append("}}").toString();
    }

    private static McpCallContext list(String cursor) {
        return new McpCallContext(null, new McpListToolsRequest(1L, cursor));
    }

    private static McpTransport answering(String response, AtomicInteger sent) {
        McpTransport delegate = mock(McpTransport.class);
        when(delegate.sendRequest(any(McpCallContext.class))).thenAnswer(invocation -> {
            sent.incrementAndGet();
            return CompletableFuture.completedFuture(response);
        });
        return delegate;
    }

    @Test
    @DisplayName("an endless cursor chain is refused at the page bound, unsent, and the owner is told")
    void endlessCursorStopsAtPageBound() {
        var sent = new AtomicInteger();
        var exceeded = new AtomicInteger();
        var transport = new BoundedListingMcpTransport(answering(page(1, "again"), sent), LIMITS, exceeded::incrementAndGet);

        // The client's loop: first page without a cursor, every later one with the
        // cursor the server handed back.
        transport.sendRequest(list(null)).join();
        transport.sendRequest(list("again")).join();
        transport.sendRequest(list("again")).join();
        CompletableFuture<String> fourth = transport.sendRequest(list("again"));

        var failure = assertThrows(ExecutionException.class, fourth::get);
        assertInstanceOf(BoundedListingMcpTransport.ListingLimitExceededException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("more than 3 pages"), failure.getCause().getMessage());
        assertEquals(3, sent.get(), "the page past the bound must never be sent");
        assertEquals(1, exceeded.get());
    }

    @Test
    @DisplayName("a request without a cursor starts a new listing, so a finite listing per turn is never refused")
    void cursorlessRequestResetsTheCount() {
        var sent = new AtomicInteger();
        var transport = new BoundedListingMcpTransport(answering(page(1, null), sent), LIMITS, null);

        for (int turn = 0; turn < 5; turn++) {
            transport.sendRequest(list(null)).join();
            transport.sendRequest(list("next")).join();
        }
        assertEquals(10, sent.get());
    }

    @Test
    @DisplayName("entries past the item bound fail the listing even on its first page")
    void itemBound() {
        var exceeded = new AtomicInteger();
        var transport = new BoundedListingMcpTransport(answering(page(11, null), new AtomicInteger()), LIMITS, exceeded::incrementAndGet);

        var failure = assertThrows(ExecutionException.class, () -> transport.sendRequest(list(null)).get());
        assertInstanceOf(BoundedListingMcpTransport.ListingLimitExceededException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("more than 10 entries"));
        assertEquals(1, exceeded.get());
    }

    @Test
    @DisplayName("items are counted across pages")
    void itemsAccumulateAcrossPages() {
        var transport = new BoundedListingMcpTransport(answering(page(4, "more"), new AtomicInteger()),
                new BoundedListingMcpTransport.Limits(100, 10, Duration.ofSeconds(30)), null);

        transport.sendRequest(list(null)).join();
        transport.sendRequest(list("more")).join();
        assertThrows(ExecutionException.class, () -> transport.sendRequest(list("more")).get(), "12 entries > 10");
    }

    @Test
    @DisplayName("a listing that outlives the deadline is refused at its next page")
    void deadline() {
        var clock = new AtomicLong();
        var transport = new BoundedListingMcpTransport(answering(page(1, "slow"), new AtomicInteger()),
                new BoundedListingMcpTransport.Limits(100, 1000, Duration.ofSeconds(30)), null, clock::get);

        transport.sendRequest(list(null)).join();
        clock.addAndGet(Duration.ofSeconds(31).toNanos());

        var failure = assertThrows(ExecutionException.class, () -> transport.sendRequest(list("slow")).get());
        assertTrue(failure.getCause().getMessage().contains("longer than 30000 ms"));
    }

    @Test
    @DisplayName("listings of different methods are counted separately")
    void perMethod() {
        var sent = new AtomicInteger();
        var transport = new BoundedListingMcpTransport(answering("{\"result\":{\"resources\":[]}}", sent), LIMITS, null);

        transport.sendRequest(list(null)).join();
        transport.sendRequest(new McpCallContext(null, new McpListResourcesRequest(2L, null))).join();
        transport.sendRequest(list("a")).join();
        transport.sendRequest(list("b")).join();
        assertEquals(4, sent.get());
    }

    @Test
    @DisplayName("a tool call passes straight through, whatever it returns")
    void nonListPassesThrough() {
        var sent = new AtomicInteger();
        McpTransport delegate = answering(page(50, "x"), sent);
        var transport = new BoundedListingMcpTransport(delegate, LIMITS, null);
        McpClientMessage call = new McpCallToolRequest(5L, "search", Map.of());

        assertEquals(page(50, "x"), transport.sendRequest(new McpCallContext(null, call)).join());
        assertEquals(1, sent.get());
    }

    @Test
    @DisplayName("the transport's default methods reach the delegate instead of being swallowed")
    void defaultMethodsDelegate() throws Exception {
        McpTransport delegate = mock(McpTransport.class);
        when(delegate.requiresCancellationNotification()).thenReturn(true);
        var transport = new BoundedListingMcpTransport(delegate, LIMITS, null);

        transport.setModernProtocol(true);
        transport.setProtocolVersion("2025-06-18");
        transport.checkHealth();
        transport.close();

        verify(delegate).setModernProtocol(true);
        verify(delegate).setProtocolVersion("2025-06-18");
        verify(delegate).checkHealth();
        verify(delegate).close();
        assertTrue(transport.requiresCancellationNotification());
        assertSame(delegate, transport.delegate());
    }

    @Test
    @DisplayName("items are counted in result.<field> and at top level; anything else counts zero")
    void countItems() {
        assertEquals(2, BoundedListingMcpTransport.countItems(page(2, "c"), "tools"));
        assertEquals(3, BoundedListingMcpTransport.countItems("{\"tools\":[1,2,{\"a\":[1]}]}", "tools"));
        assertEquals(0, BoundedListingMcpTransport.countItems("{\"result\":{\"other\":[1,2]}}", "tools"));
        assertEquals(0, BoundedListingMcpTransport.countItems("not json", "tools"));
        assertEquals(0, BoundedListingMcpTransport.countItems(null, "tools"));
        assertEquals("resourceTemplates", BoundedListingMcpTransport.itemsField(McpClientMethod.RESOURCES_TEMPLATES_LIST));
        assertNull(BoundedListingMcpTransport.itemsField(McpClientMethod.TOOLS_CALL));
        assertFalse(BoundedListingMcpTransport.itemsField(McpClientMethod.PROMPTS_LIST).isEmpty());
    }
}
