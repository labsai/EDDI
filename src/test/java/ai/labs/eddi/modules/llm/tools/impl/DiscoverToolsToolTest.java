/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DiscoverToolsTool Tests")
class DiscoverToolsToolTest {

    private List<ToolSpecification> testSpecs;

    @BeforeEach
    void setUp() {
        testSpecs = new ArrayList<>();
        testSpecs.add(ToolSpecification.builder().name("calculator").description("Perform math calculations").build());
        testSpecs.add(ToolSpecification.builder().name("websearch").description("Search the web for information").build());
        testSpecs.add(ToolSpecification.builder().name("weather").description("Get current weather data").build());
        testSpecs.add(ToolSpecification.builder().name("pdfreader").description("Read and extract text from PDF files").build());
        testSpecs.add(ToolSpecification.builder().name("dataformatter").description("Format and transform data").build());
        testSpecs.add(ToolSpecification.builder().name("discover_tools").description("Meta-tool to discover available tools").build());
    }

    @Nested
    @DisplayName("Category Filtering")
    class CategoryFilteringTests {

        @Test
        @DisplayName("Should filter by category keyword")
        void testCategoryFilter() {
            var tool = new DiscoverToolsTool(testSpecs, 20);
            String result = tool.discoverTools("math", null);

            assertTrue(result.contains("calculator"));
            assertFalse(result.contains("websearch"));
        }

        @Test
        @DisplayName("Should return all tools when no category")
        void testNoCategoryFilter() {
            var tool = new DiscoverToolsTool(testSpecs, 20);
            String result = tool.discoverTools(null, null);

            // All except discover_tools itself
            assertTrue(result.contains("calculator"));
            assertTrue(result.contains("websearch"));
            assertTrue(result.contains("weather"));
            assertTrue(result.contains("pdfreader"));
            assertTrue(result.contains("dataformatter"));
            assertFalse(result.contains("\"discover_tools\""));
        }

        @Test
        @DisplayName("Should match on description")
        void testCategoryMatchesDescription() {
            var tool = new DiscoverToolsTool(testSpecs, 20);
            String result = tool.discoverTools("PDF", null);

            assertTrue(result.contains("pdfreader"));
        }
    }

    @Nested
    @DisplayName("Keyword Filtering")
    class KeywordFilteringTests {

        @Test
        @DisplayName("Should filter by keyword in name")
        void testKeywordInName() {
            var tool = new DiscoverToolsTool(testSpecs, 20);
            String result = tool.discoverTools(null, "search");

            assertTrue(result.contains("websearch"));
        }

        @Test
        @DisplayName("Should filter by keyword in description")
        void testKeywordInDescription() {
            var tool = new DiscoverToolsTool(testSpecs, 20);
            String result = tool.discoverTools(null, "extract text");

            assertTrue(result.contains("pdfreader"));
        }

        @Test
        @DisplayName("Should return no tools message when nothing matches")
        void testNoMatches() {
            var tool = new DiscoverToolsTool(testSpecs, 20);
            String result = tool.discoverTools(null, "nonexistent_feature_xyz");

            assertTrue(result.contains("\"tools\":[]") || result.contains("\"tools\": []"));
            assertTrue(result.contains("No tools found"));
        }
    }

    @Nested
    @DisplayName("MaxToolsInContext Cap")
    class MaxToolsCapTests {

        @Test
        @DisplayName("Should cap results at maxToolsInContext")
        void testCapAtMax() {
            var tool = new DiscoverToolsTool(testSpecs, 2);
            String result = tool.discoverTools(null, null);

            // Count matches
            assertTrue(result.contains("\"count\":2") || result.contains("\"count\": 2"));
        }

        @Test
        @DisplayName("Should use default of 20 for zero maxTools")
        void testZeroMaxTools() {
            var tool = new DiscoverToolsTool(testSpecs, 0);
            String result = tool.discoverTools(null, null);

            // All 5 non-meta tools returned (5 < 20)
            assertTrue(result.contains("\"count\":5") || result.contains("\"count\": 5"));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle null tool specs list")
        void testNullToolSpecs() {
            var tool = new DiscoverToolsTool(null, 20);
            String result = tool.discoverTools(null, null);

            assertTrue(result.contains("\"tools\":[]") || result.contains("\"tools\": []"));
        }

        @Test
        @DisplayName("Should handle empty tool specs list")
        void testEmptyToolSpecs() {
            var tool = new DiscoverToolsTool(List.of(), 20);
            String result = tool.discoverTools(null, null);

            assertTrue(result.contains("\"tools\":[]") || result.contains("\"tools\": []"));
        }

        @Test
        @DisplayName("Should exclude discover_tools from results")
        void testExcludesSelf() {
            var tool = new DiscoverToolsTool(testSpecs, 20);
            String result = tool.discoverTools("discover", null);

            // discover_tools matches "discover" keyword but should be excluded
            assertFalse(result.contains("\"name\": \"discover_tools\""));
        }

        @Test
        @DisplayName("getOwnSpecs should return valid specifications")
        void testGetOwnSpecs() {
            var tool = new DiscoverToolsTool(testSpecs, 20);
            var specs = tool.getOwnSpecs();

            assertNotNull(specs);
            assertFalse(specs.isEmpty());
            assertEquals("discover_tools", specs.getFirst().name());
        }
    }
}
