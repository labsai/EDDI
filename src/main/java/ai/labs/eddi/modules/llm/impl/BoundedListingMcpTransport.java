/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.McpClientMessage;
import dev.langchain4j.mcp.protocol.McpClientMethod;
import dev.langchain4j.mcp.protocol.McpClientParams;
import dev.langchain4j.mcp.protocol.McpClientRequest;
import dev.langchain4j.mcp.protocol.McpInitializeRequest;
import dev.langchain4j.mcp.protocol.McpListPromptsParams;
import dev.langchain4j.mcp.protocol.McpListResourceTemplatesParams;
import dev.langchain4j.mcp.protocol.McpListResourcesParams;
import dev.langchain4j.mcp.protocol.McpListToolsParams;

import java.io.IOException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Bounds the paginated {@code tools/list}, {@code resources/list},
 * {@code resources/templates/list} and {@code prompts/list} listings an MCP
 * server can make the client walk.
 * <p>
 * langchain4j's {@code DefaultMcpClient} pages through a listing with {@code do
 * { addAll(page.items()); cursor = page.nextCursor(); } while (cursor != null)}
 * and only a per-request timeout. Each page is capped in bytes by
 * {@code BoundedJdkHttpClient}, the number of pages is not: a server that
 * answers every page with a fresh cursor held the discovering thread forever
 * and grew the list until the heap was gone, before any governance could look
 * at it. This wrapper sits between the client and the real transport and fails
 * the next request once a listing passes {@link Limits#maxPages() a page
 * count}, {@link Limits#maxItems() an item count} or {@link Limits#deadline() a
 * wall-clock deadline}. A failed request ends the client's loop with an
 * exception, and {@code onExceeded} lets the owner drop the client.
 * <p>
 * A listing is tracked per calling thread and per list method. The client
 * issues every page of one listing from the thread that asked for it (the
 * transport is called synchronously from {@code fetchPaginatedList}), so two
 * conversations listing the same cached client at once do not count each
 * other's pages. A request without a cursor starts a new listing.
 * <p>
 * Everything that is not a list request goes straight to the delegate, and so
 * do the transport's default methods, which an unwrapped default would
 * otherwise swallow.
 */
final class BoundedListingMcpTransport implements McpTransport {

    /**
     * The three bounds of one listing.
     *
     * @param maxPages
     *            most pages one listing may request, the first page included
     * @param maxItems
     *            most entries one listing may return across its pages
     * @param deadline
     *            longest one listing may take from its first page
     */
    record Limits(int maxPages, int maxItems, Duration deadline) {
    }

    /**
     * Thrown (as the cause of a failed request) when a listing passes a bound. Its
     * message names the bound, never anything the server sent.
     */
    static final class ListingLimitExceededException extends RuntimeException {
        ListingLimitExceededException(String message) {
            super(message);
        }
    }

    private static final JsonFactory JSON = new JsonFactory();

    private final McpTransport delegate;
    private final Limits limits;
    private final Runnable onExceeded;
    private final LongSupplier nanoClock;
    private final ThreadLocal<Map<McpClientMethod, Listing>> listings = ThreadLocal.withInitial(() -> new EnumMap<>(McpClientMethod.class));

    BoundedListingMcpTransport(McpTransport delegate, Limits limits, Runnable onExceeded) {
        this(delegate, limits, onExceeded, System::nanoTime);
    }

    BoundedListingMcpTransport(McpTransport delegate, Limits limits, Runnable onExceeded, LongSupplier nanoClock) {
        this.delegate = delegate;
        this.limits = limits;
        this.onExceeded = onExceeded != null ? onExceeded : () -> {
        };
        this.nanoClock = nanoClock;
    }

    /** The wrapped transport (tests). */
    McpTransport delegate() {
        return delegate;
    }

    /** One listing in progress on one thread. */
    private static final class Listing {
        private final long startedNanos;
        private int pages = 1;
        private final AtomicInteger items = new AtomicInteger();

        private Listing(long startedNanos) {
            this.startedNanos = startedNanos;
        }
    }

    @Override
    public CompletableFuture<String> sendRequest(McpCallContext context) {
        McpClientMessage message = context != null ? context.message() : null;
        return guarded(message, () -> delegate.sendRequest(context));
    }

    @Override
    public CompletableFuture<String> sendRequest(McpClientMessage message) {
        return guarded(message, () -> delegate.sendRequest(message));
    }

    private interface Send {
        CompletableFuture<String> send();
    }

    private CompletableFuture<String> guarded(McpClientMessage message, Send send) {
        String itemsField = message != null ? itemsField(message.method) : null;
        if (itemsField == null || !(message instanceof McpClientRequest request)) {
            return send.send();
        }
        McpClientMethod method = message.method;
        Map<McpClientMethod, Listing> perThread = listings.get();
        long now = nanoClock.getAsLong();
        Listing listing = perThread.get(method);
        if (cursorOf(request.getParams()) == null || listing == null) {
            listing = new Listing(now);
            perThread.put(method, listing);
        } else {
            listing.pages++;
            String breach = null;
            if (listing.pages > limits.maxPages()) {
                breach = "more than " + limits.maxPages() + " pages";
            } else if (Duration.ofNanos(now - listing.startedNanos).compareTo(limits.deadline()) > 0) {
                breach = "longer than " + limits.deadline().toMillis() + " ms";
            }
            if (breach != null) {
                perThread.remove(method);
                return CompletableFuture.failedFuture(exceeded(method, breach));
            }
        }
        Listing current = listing;
        return send.send().thenApply(response -> {
            int total = current.items.addAndGet(countItems(response, itemsField));
            if (total > limits.maxItems()) {
                throw exceeded(method, "more than " + limits.maxItems() + " entries");
            }
            return response;
        });
    }

    private ListingLimitExceededException exceeded(McpClientMethod method, String breach) {
        try {
            onExceeded.run();
        } catch (RuntimeException ignored) {
            // dropping the client is best effort; the request fails regardless
        }
        return new ListingLimitExceededException("MCP " + methodName(method) + " stopped: the server's listing ran " + breach
                + " (eddi.mcp.list.max-pages / max-items / deadline-ms)");
    }

    private static String methodName(McpClientMethod method) {
        return switch (method) {
            case TOOLS_LIST -> "tools/list";
            case RESOURCES_LIST -> "resources/list";
            case RESOURCES_TEMPLATES_LIST -> "resources/templates/list";
            case PROMPTS_LIST -> "prompts/list";
            default -> method.name();
        };
    }

    /**
     * The result field holding a list method's entries, or null for any other
     * method.
     */
    static String itemsField(McpClientMethod method) {
        if (method == null) {
            return null;
        }
        return switch (method) {
            case TOOLS_LIST -> "tools";
            case RESOURCES_LIST -> "resources";
            case RESOURCES_TEMPLATES_LIST -> "resourceTemplates";
            case PROMPTS_LIST -> "prompts";
            default -> null;
        };
    }

    private static String cursorOf(McpClientParams params) {
        if (params instanceof McpListToolsParams p) {
            return p.getCursor();
        }
        if (params instanceof McpListResourcesParams p) {
            return p.getCursor();
        }
        if (params instanceof McpListResourceTemplatesParams p) {
            return p.getCursor();
        }
        if (params instanceof McpListPromptsParams p) {
            return p.getCursor();
        }
        return null;
    }

    /**
     * Number of entries in {@code result.<field>} (or a top-level {@code <field>})
     * of one JSON-RPC response, counted with a streaming parser so the page is not
     * materialised a second time. Anything unreadable counts as zero: the client
     * rejects it on its own.
     */
    static int countItems(String json, String field) {
        if (json == null || json.isEmpty()) {
            return 0;
        }
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return 0;
            }
            return countIn(parser, field, true);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static int countIn(JsonParser parser, String field, boolean topLevel) throws IOException {
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            JsonToken value = parser.nextToken();
            if (field.equals(name) && value == JsonToken.START_ARRAY) {
                int count = 0;
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    count++;
                    parser.skipChildren();
                }
                return count;
            }
            if (topLevel && "result".equals(name) && value == JsonToken.START_OBJECT) {
                return countIn(parser, field, false);
            }
            parser.skipChildren();
        }
        return 0;
    }

    // ---- everything else is the delegate's ----

    @Override
    public void start(McpOperationHandler messageHandler) {
        delegate.start(messageHandler);
    }

    @Override
    public CompletableFuture<String> sendInitializeRequest(McpInitializeRequest request) {
        return delegate.sendInitializeRequest(request);
    }

    @Override
    public void sendMessage(McpCallContext context) {
        delegate.sendMessage(context);
    }

    @Override
    public void sendMessage(McpClientMessage message) {
        delegate.sendMessage(message);
    }

    @Override
    public void checkHealth() {
        delegate.checkHealth();
    }

    @Override
    public void onFailure(Runnable actionOnFailure) {
        delegate.onFailure(actionOnFailure);
    }

    @Override
    public void setModernProtocol(boolean modernProtocol) {
        delegate.setModernProtocol(modernProtocol);
    }

    @Override
    public void setProtocolVersion(String protocolVersion) {
        delegate.setProtocolVersion(protocolVersion);
    }

    @Override
    public boolean requiresCancellationNotification() {
        return delegate.requiresCancellationNotification();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
