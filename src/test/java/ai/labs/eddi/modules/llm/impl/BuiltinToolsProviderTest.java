/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link BuiltinToolsProvider} (R2 step 2), the last tool source to
 * become SPI-conformant.
 * <p>
 * The extraction collapsed a doubled if-chain — nine {@code tools.add(...)}
 * lines in the no-whitelist branch and nine
 * {@code if (whitelist.contains(...))} lines in the other — into one catalog
 * iterated once. These tests pin the two properties that made that safe: the
 * catalog contains exactly the same nine beans, and it yields them in the same
 * order for both configurations, since spec order is what the model sees.
 *
 * @author tests
 */
class BuiltinToolsProviderTest {

    private final CalculatorTool calculator = mock(CalculatorTool.class);
    private final DateTimeTool dateTime = mock(DateTimeTool.class);
    private final WebSearchTool webSearch = mock(WebSearchTool.class);
    private final DataFormatterTool dataFormatter = mock(DataFormatterTool.class);
    private final WebScraperTool webScraper = mock(WebScraperTool.class);
    private final TextSummarizerTool textSummarizer = mock(TextSummarizerTool.class);
    private final PdfReaderTool pdfReader = mock(PdfReaderTool.class);
    private final WeatherTool weather = mock(WeatherTool.class);
    private final FetchToolResponsePageTool fetchPage = mock(FetchToolResponsePageTool.class);

    private BuiltinToolsProvider provider() {
        return new BuiltinToolsProvider(calculator, dateTime, webSearch, dataFormatter, webScraper,
                textSummarizer, pdfReader, weather, fetchPage);
    }

    private ToolAssemblyContext ctx(List<String> whitelist) {
        return ctx(whitelist, true);
    }

    private ToolAssemblyContext ctx(List<String> whitelist, Boolean enableBuiltInTools) {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(enableBuiltInTools);
        task.setBuiltInToolsWhitelist(whitelist);
        return new ToolAssemblyContext(mock(IConversationMemory.class), task, whitelist, null,
                "user-1", "agent-1", null);
    }

    /** The catalog's declaration order — the old if-chain's order, verbatim. */
    private List<Object> allNineInOrder() {
        return List.of(calculator, dateTime, webSearch, dataFormatter, webScraper,
                textSummarizer, pdfReader, weather, fetchPage);
    }

    @Test
    void source_isBuiltin() {
        assertEquals("builtin", provider().source());
    }

    // =================================================================
    // collect — the catalog replacing the doubled if-chain
    // =================================================================

    @Test
    void noWhitelist_yieldsAllNineInTheOldIfChainOrder() {
        assertEquals(allNineInOrder(), provider().collect(ctx(null)));
    }

    @Test
    void emptyWhitelist_isTreatedAsNoWhitelist() {
        assertEquals(allNineInOrder(), provider().collect(ctx(List.of())));
    }

    @Test
    void whitelist_selectsOnlyTheNamedTools() {
        assertEquals(List.of(calculator, weather), provider().collect(ctx(List.of("calculator", "weather"))));
    }

    /**
     * Catalog order governs, not whitelist order — matching the old if-chain, whose
     * sequence of {@code if} statements decided the result regardless of how the
     * agent's whitelist was written.
     */
    @Test
    void whitelistOrderDoesNotAffectResultOrder() {
        assertEquals(List.of(calculator, webSearch, weather),
                provider().collect(ctx(List.of("weather", "websearch", "calculator"))));
    }

    /**
     * {@code fetchToolResponsePageTool} answers to two keys. Listing both as
     * aliases of one catalog entry (rather than as two entries) is what stops a
     * whitelist naming both from registering the bean twice — which would have
     * produced duplicate specs for the same tool.
     */
    @Test
    void fetchPage_bothAliasesSelectIt() {
        assertEquals(List.of(fetchPage), provider().collect(ctx(List.of("fetch_page"))));
        assertEquals(List.of(fetchPage), provider().collect(ctx(List.of("fetch_tool_response_page"))));
    }

    @Test
    void fetchPage_bothAliasesAtOnce_stillAddsItExactlyOnce() {
        assertEquals(List.of(fetchPage),
                provider().collect(ctx(List.of("fetch_page", "fetch_tool_response_page"))));
    }

    @Test
    void unknownWhitelistKey_selectsNothing() {
        assertTrue(provider().collect(ctx(List.of("no_such_tool"))).isEmpty());
    }

    // =================================================================
    // contribute — the enableBuiltInTools gate
    // =================================================================

    @Test
    void contribute_builtInToolsDisabled_returnsEmptyEvenWithAStaleWhitelist() {
        var contribution = provider().contribute(ctx(List.of("websearch"), false));

        assertTrue(contribution.specs().isEmpty(), "the switch that turns this category off must win");
    }

    @Test
    void contribute_enableFlagNull_countsAsDisabled() {
        var contribution = provider().contribute(ctx(null, null));

        assertTrue(contribution.specs().isEmpty(), "null is the default and means off");
    }

    @Test
    void contribute_whitelistSelectsNothing_returnsEmpty() {
        var contribution = provider().contribute(ctx(List.of("no_such_tool")));

        assertTrue(contribution.specs().isEmpty());
        assertTrue(contribution.executors().isEmpty());
    }
}
