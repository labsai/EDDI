/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * In-memory store for paginated tool responses. When a tool response exceeds
 * the configured character limit and the truncation strategy is
 * {@code paginate}, the full response is split into pages and stored here. The
 * LLM can then retrieve subsequent pages via the
 * {@code fetch_tool_response_page} built-in tool.
 * <p>
 * Pages are stored in a Caffeine cache with a 15-minute TTL — long enough for a
 * multi-turn tool-calling loop but short enough to prevent memory leaks.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class PaginatedResponseStore {

    private static final Logger LOGGER = Logger.getLogger(PaginatedResponseStore.class);
    private static final String CACHE_NAME = "paginated-tool-responses";
    private static final long PAGE_TTL_SECONDS = 900; // 15 minutes

    @Inject
    ICacheFactory cacheFactory;

    private ICache<String, PaginatedResponse> cache;

    @PostConstruct
    void init() {
        cache = cacheFactory.getCache(CACHE_NAME);
        LOGGER.info("PaginatedResponseStore initialized");
    }

    /**
     * Split a tool response into pages and store them.
     *
     * @param toolName
     *            name of the tool that produced the response
     * @param fullResponse
     *            the complete tool response string
     * @param pageSize
     *            maximum characters per page
     * @return a responseId that can be used to retrieve pages
     */
    public String store(String toolName, String fullResponse, int pageSize) {
        if (fullResponse == null || fullResponse.isEmpty() || pageSize <= 0) {
            return null;
        }

        String responseId = UUID.randomUUID().toString();
        List<String> pages = splitIntoPages(fullResponse, pageSize);

        cache.put(responseId, new PaginatedResponse(toolName, pages, fullResponse.length()), PAGE_TTL_SECONDS, TimeUnit.SECONDS);
        LOGGER.debugf("Stored %d pages for tool '%s' (responseId=%s, totalChars=%d)", pages.size(), toolName, responseId, fullResponse.length());

        return responseId;
    }

    /**
     * Retrieve a specific page of a paginated response.
     *
     * @param responseId
     *            the ID returned by {@link #store}
     * @param pageNumber
     *            1-indexed page number
     * @return the page content, or null if not found
     */
    public PageResult getPage(String responseId, int pageNumber) {
        PaginatedResponse response = cache.get(responseId);
        if (response == null) {
            return null;
        }

        if (pageNumber < 1 || pageNumber > response.pages().size()) {
            return new PageResult(null, response.pages().size(), response.totalCharacters(), response.toolName(),
                    "Page " + pageNumber + " out of range (1-" + response.pages().size() + ")");
        }

        return new PageResult(response.pages().get(pageNumber - 1), response.pages().size(), response.totalCharacters(), response.toolName(), null);
    }

    /**
     * Get the total number of pages for a response.
     */
    public int getPageCount(String responseId) {
        PaginatedResponse response = cache.get(responseId);
        return response != null ? response.pages().size() : 0;
    }

    private List<String> splitIntoPages(String text, int pageSize) {
        int totalLength = text.length();
        int pageCount = (totalLength + pageSize - 1) / pageSize;
        var pages = new java.util.ArrayList<String>(pageCount);

        for (int i = 0; i < totalLength; i += pageSize) {
            pages.add(text.substring(i, Math.min(i + pageSize, totalLength)));
        }

        return List.copyOf(pages);
    }

    // === Records ===

    record PaginatedResponse(String toolName, List<String> pages, int totalCharacters) {
    }

    /**
     * Result of a page retrieval.
     *
     * @param content
     *            the page content (null if error)
     * @param totalPages
     *            total number of pages
     * @param totalCharacters
     *            total characters in the full response
     * @param toolName
     *            the tool that produced the response
     * @param error
     *            error message if page retrieval failed (null on success)
     */
    public record PageResult(String content, int totalPages, int totalCharacters, String toolName, String error) {
        public boolean isSuccess() {
            return error == null && content != null;
        }
    }
}
