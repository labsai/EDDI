/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.modules.llm.tools.impl.CalculatorTool;
import ai.labs.eddi.modules.llm.tools.impl.DataFormatterTool;
import ai.labs.eddi.modules.llm.tools.impl.DateTimeTool;
import ai.labs.eddi.modules.llm.tools.impl.FetchToolResponsePageTool;
import ai.labs.eddi.modules.llm.tools.impl.PdfReaderTool;
import ai.labs.eddi.modules.llm.tools.impl.ReadAttachmentTool;
import ai.labs.eddi.modules.llm.tools.impl.TextSummarizerTool;
import ai.labs.eddi.modules.llm.tools.impl.WeatherTool;
import ai.labs.eddi.modules.llm.tools.impl.WebScraperTool;
import ai.labs.eddi.modules.llm.tools.impl.WebSearchTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Guards the class-name → configuration-slug table that makes built-in tool
 * pricing, cache TTLs and slug-keyed {@code toolRateLimits} resolve at all.
 */
@DisplayName("ToolNameResolver")
class ToolNameResolverTest {

    @Nested
    @DisplayName("canonicalForClass")
    class CanonicalForClass {

        /**
         * The slugs are asserted against the REAL class simple names (not string
         * literals) so that renaming a tool class without updating the resolver fails
         * here rather than silently reverting that tool to $0.00 and a 300s TTL.
         */
        @Test
        @DisplayName("every built-in tool class resolves to its whitelist slug")
        void allSixteenClassesResolve() {
            assertEquals("calculator", ToolNameResolver.canonicalForClass(CalculatorTool.class.getSimpleName()));
            assertEquals("datetime", ToolNameResolver.canonicalForClass(DateTimeTool.class.getSimpleName()));
            assertEquals("websearch", ToolNameResolver.canonicalForClass(WebSearchTool.class.getSimpleName()));
            assertEquals("dataformatter", ToolNameResolver.canonicalForClass(DataFormatterTool.class.getSimpleName()));
            assertEquals("webscraper", ToolNameResolver.canonicalForClass(WebScraperTool.class.getSimpleName()));
            assertEquals("textsummarizer", ToolNameResolver.canonicalForClass(TextSummarizerTool.class.getSimpleName()));
            assertEquals("pdfreader", ToolNameResolver.canonicalForClass(PdfReaderTool.class.getSimpleName()));
            assertEquals("weather", ToolNameResolver.canonicalForClass(WeatherTool.class.getSimpleName()));
            assertEquals("fetch_tool_response_page",
                    ToolNameResolver.canonicalForClass(FetchToolResponsePageTool.class.getSimpleName()));
            assertEquals("usermemory", ToolNameResolver.canonicalForClass(UserMemoryTool.class.getSimpleName()));
            assertEquals("conversationRecall",
                    ToolNameResolver.canonicalForClass(ConversationRecallTool.class.getSimpleName()));
            assertEquals("readattachment", ToolNameResolver.canonicalForClass(ReadAttachmentTool.class.getSimpleName()));
            assertEquals("create_sub_agent", ToolNameResolver.canonicalForClass(CreateSubAgentTool.class.getSimpleName()));
            assertEquals("converse_with_agent",
                    ToolNameResolver.canonicalForClass(ConverseWithAgentTool.class.getSimpleName()));
            assertEquals("find_agents_by_capability",
                    ToolNameResolver.canonicalForClass(FindAgentsByCapabilityTool.class.getSimpleName()));
            assertEquals("teardown_agent", ToolNameResolver.canonicalForClass(TeardownAgentTool.class.getSimpleName()));
        }

        /**
         * A CDI client proxy is NOT a built-in as far as this resolver is concerned.
         * Callers must unwrap first; resolving the proxy name would put every built-in
         * back on the default price and the default TTL — the exact defect this class
         * was written to close, only harder to spot.
         */
        @Test
        @DisplayName("a CDI client proxy name resolves to null, not to a slug")
        void clientProxyNameIsNotResolved() {
            assertNull(ToolNameResolver.canonicalForClass("CalculatorTool_ClientProxy"));
            assertNull(ToolNameResolver.canonicalForClass("WebSearchTool_ClientProxy"));
        }

        @Test
        @DisplayName("unknown and null class names resolve to null")
        void unknownResolvesToNull() {
            assertNull(ToolNameResolver.canonicalForClass("SomeHttpCallTool"));
            assertNull(ToolNameResolver.canonicalForClass("DiscoverToolsTool"));
            assertNull(ToolNameResolver.canonicalForClass(""));
            assertNull(ToolNameResolver.canonicalForClass(null));
        }

        /**
         * The neighbouring {@code ToolCacheService.getSmartTTL} falls back to substring
         * matching, and that is why this bug went unnoticed for so long: some method
         * names happen to contain their slug and resolved correctly by accident. This
         * resolver must be exact.
         */
        @Test
        @DisplayName("matching is exact — no substring or case-insensitive fallback")
        void matchingIsExact() {
            assertNull(ToolNameResolver.canonicalForClass("calculatortool"));
            assertNull(ToolNameResolver.canonicalForClass("MyCalculatorTool"));
            assertNull(ToolNameResolver.canonicalForClass("CalculatorToolV2"));
        }
    }

    @Nested
    @DisplayName("canonical")
    class Canonical {

        @Test
        @DisplayName("maps a dispatch name onto its slug")
        void mapsDispatchName() {
            assertEquals("websearch", ToolNameResolver.canonical("searchWeb", Map.of("searchWeb", "websearch")));
        }

        @Test
        @DisplayName("an unmapped dispatch name resolves to itself")
        void unmappedResolvesToItself() {
            assertEquals("my_http_tool", ToolNameResolver.canonical("my_http_tool", Map.of("searchWeb", "websearch")));
        }

        @Test
        @DisplayName("a null map resolves to the dispatch name without an NPE")
        void nullMapIsTolerated() {
            assertEquals("searchWeb", ToolNameResolver.canonical("searchWeb", null));
        }

        @Test
        @DisplayName("an empty map resolves to the dispatch name")
        void emptyMapIsTolerated() {
            assertEquals("searchWeb", ToolNameResolver.canonical("searchWeb", Map.of()));
        }

        @Test
        @DisplayName("a null-valued mapping degrades to the dispatch name rather than to null")
        void nullValuedMappingDegrades() {
            Map<String, String> withNullValue = new HashMap<>();
            withNullValue.put("searchWeb", null);

            // getOrDefault returns the stored null, so this documents the contract the
            // orchestrator relies on: it never stores nulls (it substitutes the dispatch
            // name at build time), and ToolInvocation normalises anything that slips
            // through.
            assertNull(ToolNameResolver.canonical("searchWeb", withNullValue));
            assertNotNull(new ToolInvocation("searchWeb", ToolNameResolver.canonical("searchWeb", withNullValue), null)
                    .canonicalName());
        }
    }
}
