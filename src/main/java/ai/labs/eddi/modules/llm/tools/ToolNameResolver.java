/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import java.util.Map;

/**
 * Maps a built-in tool class onto the slug an agent designer configures it
 * under.
 *
 * <p>
 * The slugs returned here are exactly the tokens accepted by
 * {@code builtInToolsWhitelist}, and therefore exactly the keys operators are
 * documented to use in {@code toolRateLimits} and {@code toolPricing}. Nothing
 * else may be returned: a slug that is not a whitelist token would create a
 * third naming vocabulary and re-open the mismatch this class exists to close.
 * </p>
 *
 * <p>
 * <strong>The switch is exhaustive and exact — deliberately no substring
 * matching.</strong> The neighbouring {@code ToolCacheService.getSmartTTL}
 * falls back to {@code lowerToolName.contains(key)}, and that is precisely how
 * this bug shipped unnoticed: {@code getCurrentDateTime} happens to contain
 * "datetime" and resolved correctly, while {@code calculate} does not contain
 * "calculator" and silently fell through to a default. A lookup that is right
 * by coincidence for some inputs is worse than one that is wrong for all of
 * them, because it never looks broken. An unknown class name returns
 * {@code null} so the caller can fall back explicitly.
 * </p>
 *
 * <p>
 * Stateless and all-static; safe to call from any thread.
 * </p>
 */
public final class ToolNameResolver {

    private ToolNameResolver() {
        // utility class
    }

    /**
     * Resolves a built-in tool's <em>bean class simple name</em> to its
     * configuration slug.
     *
     * <p>
     * The caller MUST pass the simple name of the real bean class, not of a CDI
     * client proxy. {@code CalculatorTool_ClientProxy} is not a case here and
     * yields {@code null}, which would put every built-in back at the default price
     * and the default TTL — the same defect wearing a different coat.
     * {@code AgentOrchestrator.buildToolSetup} already unwraps the proxy before
     * reflecting over {@code @Tool} methods; reuse that unwrapped class.
     * </p>
     *
     * @param toolClassSimpleName
     *            simple name of the (unwrapped) tool bean class
     * @return the whitelist slug, or {@code null} when the class is not a built-in
     */
    public static String canonicalForClass(String toolClassSimpleName) {
        if (toolClassSimpleName == null) {
            return null;
        }

        return switch (toolClassSimpleName) {
            case "CalculatorTool" -> "calculator";
            case "DateTimeTool" -> "datetime";
            case "WebSearchTool" -> "websearch";
            case "DataFormatterTool" -> "dataformatter";
            case "WebScraperTool" -> "webscraper";
            case "TextSummarizerTool" -> "textsummarizer";
            case "PdfReaderTool" -> "pdfreader";
            case "WeatherTool" -> "weather";
            case "FetchToolResponsePageTool" -> "fetch_tool_response_page";
            case "UserMemoryTool" -> "usermemory";
            case "ConversationRecallTool" -> "conversationRecall";
            case "ReadAttachmentTool" -> "readattachment";
            case "CreateSubAgentTool" -> "create_sub_agent";
            case "ConverseWithAgentTool" -> "converse_with_agent";
            case "FindAgentsByCapabilityTool" -> "find_agents_by_capability";
            case "TeardownAgentTool" -> "teardown_agent";
            // DiscoverToolsTool is intentionally absent: discover_tools is a LAZY-mode
            // meta-tool that is never whitelisted, priced or rate-limited by slug, and it
            // already declares @Tool(name = "discover_tools") so dispatch name == slug.
            default -> null;
        };
    }

    /**
     * Resolves a dispatch name to its canonical slug using the map built by
     * {@code AgentOrchestrator.buildToolSetup}.
     *
     * <p>
     * Tools with no mapping — http, mcp, a2a and the dynamic-agent tools, whose
     * dispatch name IS their configured name — resolve to themselves, so callers
     * never need a null check. A key present with a {@code null} value is treated
     * the same as an absent key: {@code getOrDefault} would hand back the stored
     * {@code null}, which would break that guarantee and propagate a null slug into
     * price and TTL lookups.
     * </p>
     *
     * @param dispatchName
     *            the name the model invoked
     * @param canonicalNames
     *            dispatch name → slug, may be {@code null}
     * @return the slug, or {@code dispatchName} when there is no (non-null) mapping
     */
    public static String canonical(String dispatchName, Map<String, String> canonicalNames) {
        if (canonicalNames == null) {
            return dispatchName;
        }
        String slug = canonicalNames.get(dispatchName);
        return slug != null ? slug : dispatchName;
    }
}
