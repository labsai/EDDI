/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.tools.impl.CalculatorTool;
import ai.labs.eddi.modules.llm.tools.impl.DataFormatterTool;
import ai.labs.eddi.modules.llm.tools.impl.DateTimeTool;
import ai.labs.eddi.modules.llm.tools.impl.FetchToolResponsePageTool;
import ai.labs.eddi.modules.llm.tools.impl.PdfReaderTool;
import ai.labs.eddi.modules.llm.tools.impl.TextSummarizerTool;
import ai.labs.eddi.modules.llm.tools.impl.WeatherTool;
import ai.labs.eddi.modules.llm.tools.impl.WebScraperTool;
import ai.labs.eddi.modules.llm.tools.impl.WebSearchTool;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The nine plain built-in tool beans — calculator, datetime, web search, data
 * formatter, web scraper, text summarizer, PDF reader, weather, and
 * tool-response paging (R2 step 2, the last source to become SPI-conformant).
 * <p>
 * These were the only tools left inline in {@code AgentOrchestrator} after the
 * other four providers were extracted, and they were the reason
 * {@code buildToolSetup} could not yet iterate providers: every other source
 * had a home, so the if-chain was both the largest remaining block and the one
 * thing blocking the rewiring.
 * <p>
 * <b>The if-chain is now a catalog.</b> {@code collectAllBuiltInTools} carried
 * the nine tools twice — once as {@code if (whitelist.contains(...))} lines and
 * once as unconditional {@code tools.add(...)} lines in the no-whitelist
 * branch. Two lists of the same nine beans in the same order is a drift hazard
 * for nothing: adding a tool to one branch and forgetting the other silently
 * changes behaviour for exactly one of the two configurations. {@link #catalog}
 * declares each tool once with the whitelist keys that select it, and one loop
 * serves both branches — no whitelist means every entry applies, which is what
 * "no whitelist" has always meant.
 * <p>
 * Order is load-bearing and preserved: the catalog is iterated in declaration
 * order, matching the old if-chain, so the resulting tool list (and therefore
 * the spec order the model sees) is byte-identical for every configuration.
 * Note that this is the *declaration* order, not the order keys appear in an
 * agent's whitelist — the old code had the same property, since the if-chain's
 * sequence governed, not the whitelist's.
 * <p>
 * Constructed once in the {@code AgentOrchestrator} constructor rather than per
 * call: unlike {@code ContextualToolsProvider} and
 * {@code DynamicAgentToolsProvider}, every dependency here is a {@code final}
 * constructor-injected bean, so there is no null-at-construction-time problem
 * to work around.
 */
class BuiltinToolsProvider implements ToolSourceProvider {

    /**
     * One catalog entry: the whitelist keys that select a tool, and the bean.
     *
     * @param whitelistKeys
     *            every key an agent's {@code builtInToolsWhitelist} may use for
     *            this tool. Usually one; {@code fetchToolResponsePageTool} accepts
     *            both {@code fetch_page} and {@code fetch_tool_response_page}, and
     *            listing both here (rather than as two catalog entries) is what
     *            keeps a whitelist naming both from adding the same bean twice.
     * @param bean
     *            the tool instance
     */
    private record CatalogEntry(Set<String> whitelistKeys, Object bean) {

        boolean selectedBy(ToolAssemblyContext ctx) {
            return ctx.hasNoWhitelist() || whitelistKeys.stream().anyMatch(ctx::isWhitelisted);
        }
    }

    private final List<CatalogEntry> catalog;

    BuiltinToolsProvider(CalculatorTool calculatorTool, DateTimeTool dateTimeTool, WebSearchTool webSearchTool,
            DataFormatterTool dataFormatterTool, WebScraperTool webScraperTool, TextSummarizerTool textSummarizerTool,
            PdfReaderTool pdfReaderTool, WeatherTool weatherTool, FetchToolResponsePageTool fetchToolResponsePageTool) {
        // Declaration order == the old if-chain's order. Do not sort.
        this.catalog = List.of(
                new CatalogEntry(Set.of("calculator"), calculatorTool),
                new CatalogEntry(Set.of("datetime"), dateTimeTool),
                new CatalogEntry(Set.of("websearch"), webSearchTool),
                new CatalogEntry(Set.of("dataformatter"), dataFormatterTool),
                new CatalogEntry(Set.of("webscraper"), webScraperTool),
                new CatalogEntry(Set.of("textsummarizer"), textSummarizerTool),
                new CatalogEntry(Set.of("pdfreader"), pdfReaderTool),
                new CatalogEntry(Set.of("weather"), weatherTool),
                new CatalogEntry(Set.of("fetch_page", "fetch_tool_response_page"), fetchToolResponsePageTool));
    }

    @Override
    public String source() {
        return "builtin";
    }

    /**
     * Gated on {@code enableBuiltInTools} exactly as the live path is: it is the
     * switch that turns this whole category off, and {@code null} counts as off
     * because {@code null} is the default. A stale whitelist naming
     * {@code "websearch"} on an agent with built-ins disabled gets nothing, which
     * is what happens today.
     */
    @Override
    public ToolContribution contribute(ToolAssemblyContext ctx) {
        Boolean enableBuiltInTools = ctx.task().getEnableBuiltInTools();
        if (enableBuiltInTools == null || !enableBuiltInTools) {
            return ToolContribution.empty();
        }
        List<Object> tools = collect(ctx);
        if (tools.isEmpty()) {
            return ToolContribution.empty();
        }
        var reflected = ToolObjectReflector.reflect(tools);
        return new ToolContribution(reflected.specs(), reflected.executors(), reflected.toolSources(), Map.of(),
                List.of(), reflected.toolCanonicalNames());
    }

    /**
     * The tool beans this turn's configuration selects, in catalog order.
     * <p>
     * Deliberately does <em>not</em> apply the {@code enableBuiltInTools} switch —
     * {@code AgentOrchestrator#collectAllBuiltInTools} checks that once, before
     * calling any source, and calls this from inside both of its whitelist
     * branches. Package-private so that call site can use it while
     * {@code buildToolSetup} still assembles tools the old way.
     */
    List<Object> collect(ToolAssemblyContext ctx) {
        List<Object> tools = new ArrayList<>();
        for (CatalogEntry entry : catalog) {
            if (entry.selectedBy(ctx)) {
                tools.add(entry.bean());
            }
        }
        return tools;
    }
}
