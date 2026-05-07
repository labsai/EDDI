/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.modules.llm.tools.PaginatedResponseStore;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Built-in tool that allows the LLM to retrieve subsequent pages of a paginated
 * tool response. When a tool result exceeds the configured character limit and
 * the truncation strategy is {@code paginate}, only the first page is returned
 * inline with a {@code responseId}. The LLM can then call this tool to fetch
 * remaining pages.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class FetchToolResponsePageTool {

    @Inject
    PaginatedResponseStore paginatedResponseStore;

    @Tool(name = "fetch_tool_response_page", value = "Retrieve a specific page of a previously paginated tool response. " +
            "Use this when a tool result was truncated and a responseId was provided.")
    public String fetchPage(
                            @P("The responseId from the truncated tool response") String responseId,
                            @P("The page number to retrieve (1-indexed)") int pageNumber) {

        if (responseId == null || responseId.isBlank()) {
            return "{\"error\": \"responseId is required\"}";
        }

        if (pageNumber < 1) {
            return "{\"error\": \"pageNumber must be >= 1\"}";
        }

        PaginatedResponseStore.PageResult result = paginatedResponseStore.getPage(responseId, pageNumber);

        if (result == null) {
            return "{\"error\": \"Response not found. It may have expired (15 minute TTL).\"}";
        }

        if (!result.isSuccess()) {
            return "{\"error\": \"" + result.error() + "\", \"totalPages\": " + result.totalPages() + "}";
        }

        return "{\"page\": " + pageNumber +
                ", \"totalPages\": " + result.totalPages() +
                ", \"toolName\": \"" + result.toolName() + "\"" +
                ", \"content\": " + escapeJson(result.content()) + "}";
    }

    private static String escapeJson(String value) {
        if (value == null)
            return "null";
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
