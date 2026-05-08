/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.modules.llm.tools.PaginatedResponseStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    PaginatedResponseStore paginatedResponseStore;

    @Tool(name = "fetch_tool_response_page", value = "Retrieve a specific page of a previously paginated tool response. " +
            "Use this when a tool result was truncated and a responseId was provided.")
    public String fetchPage(
                            @P("The responseId from the truncated tool response") String responseId,
                            @P("The page number to retrieve (1-indexed)") int pageNumber) {

        if (responseId == null || responseId.isBlank()) {
            return toJson(Map.of("error", "responseId is required"));
        }

        if (pageNumber < 1) {
            return toJson(Map.of("error", "pageNumber must be >= 1"));
        }

        PaginatedResponseStore.PageResult result = paginatedResponseStore.getPage(responseId, pageNumber);

        if (result == null) {
            return toJson(Map.of("error", "Response not found. It may have expired (15 minute TTL)."));
        }

        if (!result.isSuccess()) {
            Map<String, Object> errorResponse = new LinkedHashMap<>();
            errorResponse.put("error", result.error());
            errorResponse.put("totalPages", result.totalPages());
            return toJson(errorResponse);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("page", pageNumber);
        response.put("totalPages", result.totalPages());
        response.put("toolName", result.toolName());
        response.put("content", result.content());
        return toJson(response);
    }

    private static String toJson(Map<String, ?> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{\"error\": \"Failed to serialize response\"}";
        }
    }
}
