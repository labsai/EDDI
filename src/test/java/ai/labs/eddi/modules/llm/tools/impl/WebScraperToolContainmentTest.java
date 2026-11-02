/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.SsrfPolicy;
import ai.labs.eddi.engine.httpclient.outbound.TestOutbound;
import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import ai.labs.eddi.modules.llm.tools.ToolFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jsoup.select.QueryParser;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The web scraper's defences against model-written selectors and runaway
 * parsing: regex selectors are refused before any fetch, and CPU-bound parsing
 * runs on a bounded pool with a hard deadline.
 */
class WebScraperToolContainmentTest {

    private static final String PAGE = "<html><head><title>T</title></head><body><p>alpha</p><p>beta</p></body></html>";

    private final OutboundHttp httpClient = mock(OutboundHttp.class);
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void releaseWorkers() {
        release.countDown();
    }

    private void servePage() throws Exception {
        when(httpClient.send(any(OutboundRequest.class), any()))
                .thenReturn(TestOutbound.response(200, PAGE.getBytes(StandardCharsets.UTF_8), false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"p:matches((a+)+b)", "p:matchesOwn(x)", "div:MATCHES(x)", "p:matchText", "p:matchesWholeText(.*)",
            "p:matchesWholeOwnText(.*)", "a[href~=(a|a)*b]"})
    @DisplayName("regex selectors are refused without fetching the page")
    void regexSelectorsAreRefused(String selector) throws Exception {
        var tool = new WebScraperTool(httpClient, new HtmlToMarkdownConverter());

        String result = tool.extractWithSelector("https://example.com", selector);

        assertTrue(result.startsWith("Error: regular-expression selectors"), result);
        verify(httpClient, never()).send(any(OutboundRequest.class), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"p:\\6d atches(^h)", "p:\\6D atchesOwn(x)", "p\\:x"})
    @DisplayName("a CSS-escaped selector is refused: jsoup decodes it into a regex pseudo-selector the name check never saw")
    void escapedSelectorsAreRefused(String selector) throws Exception {
        var tool = new WebScraperTool(httpClient, new HtmlToMarkdownConverter());

        String result = tool.extractWithSelector("https://example.com", selector);

        assertTrue(result.startsWith("Error: CSS escapes"), result);
        verify(httpClient, never()).send(any(OutboundRequest.class), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"b ~ i", "b ~ b ~ i", "h1 + p", "li+li", "a ~ b", "a+b", "a + b", "div:has(+ p)", "div:not(~ p)",
            "ul:not(li ~ li)", "p:contains(x) + b", "a[href] ~ b", "a[x] ~ b", "\"x\" ~ b", "td:nth-child(2n+1) + td",
            "a[x ~ b", "a\"x ~ b", "p:contains(x ~ b", "p:contains('x) ~ b", "div:HAS(+ p)",
            "p:CONTAINS(C++)"})
    @DisplayName("sibling combinators are refused: their evaluation is quadratic in a page's width")
    void siblingCombinatorsAreRefused(String selector) throws Exception {
        var tool = new WebScraperTool(httpClient, new HtmlToMarkdownConverter());

        String result = tool.extractWithSelector("https://example.com", selector);

        assertTrue(result.startsWith("Error: sibling combinators"), result);
        verify(httpClient, never()).send(any(OutboundRequest.class), any());
    }

    @Test
    @DisplayName("an over-long or blank selector is refused; ordinary selectors pass")
    void selectorShape() {
        assertNotNull(WebScraperTool.rejectSelector("p".repeat(WebScraperTool.MAX_SELECTOR_LENGTH + 1)));
        assertNotNull(WebScraperTool.rejectSelector("  "));
        assertNotNull(WebScraperTool.rejectSelector(null));
        assertNull(WebScraperTool.rejectSelector("table tr td:eq(1)"));
        assertNull(WebScraperTool.rejectSelector("p:contains(matches)"));
        assertNull(WebScraperTool.rejectSelector("a[href^=https]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tr:nth-child(2n+1)", "li:nth-last-of-type(-n+3)", "p:contains(C++)", "p:containsOwn(a ~ b)",
            "a[href*=a+b]", "a[href*=\"a+b\"]", "a[title='x + y']", "a[title=\"~\"]", "p:contains((a+b))", "li:nth-child(2n+1)",
            "p:containsWholeOwnText(a+b)"})
    @DisplayName("'+' and '~' that are not combinators — in a formula, text argument, attribute or quote — pass")
    void nonCombinatorPlusAndTildePass(String selector) {
        assertNull(WebScraperTool.rejectSelector(selector), selector);
        // ...and jsoup itself reads them as one valid selector, not as text the scan
        // skipped while jsoup tokenized it differently.
        assertDoesNotThrow(() -> QueryParser.parse(selector), selector);
    }

    @Test
    @DisplayName("the model-chosen URL is sent STRICT, so its first hop gets the post-connect re-check too")
    void modelChosenUrlIsSentStrict() throws Exception {
        servePage();
        var tool = new WebScraperTool(httpClient, new HtmlToMarkdownConverter());

        tool.extractWithSelector("https://example.com", "p");

        var request = ArgumentCaptor.forClass(OutboundRequest.class);
        verify(httpClient).send(request.capture(), any());
        assertEquals(SsrfPolicy.STRICT, request.getValue().ssrfOverride());
    }

    @Test
    @DisplayName("an allowed selector still extracts")
    void allowedSelectorExtracts() throws Exception {
        servePage();
        var tool = new WebScraperTool(httpClient, new HtmlToMarkdownConverter());

        String result = tool.extractWithSelector("https://example.com", "p");

        assertTrue(result.contains("alpha") && result.contains("beta"), result);
    }

    @Test
    @DisplayName("parsing that outlives the deadline is abandoned and reported as a failure")
    void parseDeadline() throws Exception {
        servePage();
        HtmlToMarkdownConverter slow = mock(HtmlToMarkdownConverter.class);
        when(slow.convert(anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            release.await(10, TimeUnit.SECONDS);
            return "late";
        });
        var tool = new WebScraperTool(httpClient, slow, WebScraperTool.DEFAULT_MAX_RESPONSE_BYTES, 100, 1);

        long started = System.nanoTime();
        var failure = assertThrows(ToolFailureException.class, () -> tool.extractWebPageText("https://example.com"));

        assertTrue(failure.getMessage().contains("took longer than 100ms"), failure.getMessage());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000, "the caller is released at the deadline");
    }

    @Test
    @DisplayName("back-to-back calls on a one-worker pool are never refused as busy")
    void sequentialCallsAreNeverBusy() throws Exception {
        servePage();
        var tool = new WebScraperTool(httpClient, new HtmlToMarkdownConverter(), WebScraperTool.DEFAULT_MAX_RESPONSE_BYTES, 10_000, 1);

        for (int i = 0; i < 300; i++) {
            String result = tool.extractWithSelector("https://example.com", "p");
            assertTrue(result.contains("alpha"), "call " + i + ": " + result);
        }
    }

    @Test
    @DisplayName("a runaway keeps its worker until it really ends, then the worker is available again")
    void permitReturnsWhenTheRunawayEnds() throws Exception {
        servePage();
        CountDownLatch finished = new CountDownLatch(1);
        HtmlToMarkdownConverter slowOnce = mock(HtmlToMarkdownConverter.class);
        when(slowOnce.convert(anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            // Ignores the interrupt, like jsoup evaluating a selector does.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (release.getCount() > 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            finished.countDown();
            return "late";
        }).thenReturn("fast");
        var tool = new WebScraperTool(httpClient, slowOnce, WebScraperTool.DEFAULT_MAX_RESPONSE_BYTES, 100, 1);

        assertThrows(ToolFailureException.class, () -> tool.extractWebPageText("https://example.com"));
        var busy = assertThrows(ToolFailureException.class, () -> tool.extractWebPageText("https://example.com"));
        assertTrue(busy.getMessage().contains("busy"), "the abandoned runaway still holds the only worker: " + busy.getMessage());

        release.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        String after = null;
        for (int attempt = 0; attempt < 50 && after == null; attempt++) {
            try {
                after = tool.extractWebPageText("https://example.com");
            } catch (ToolFailureException stillReleasing) {
                Thread.sleep(20);
            }
        }
        assertEquals("fast", after);
    }

    @Test
    @DisplayName("a saturated parse pool refuses at once instead of queueing behind a runaway")
    void saturatedPoolRefuses() throws Exception {
        servePage();
        CountDownLatch entered = new CountDownLatch(1);
        HtmlToMarkdownConverter blocking = mock(HtmlToMarkdownConverter.class);
        when(blocking.convert(anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return "done";
        });
        var tool = new WebScraperTool(httpClient, blocking, WebScraperTool.DEFAULT_MAX_RESPONSE_BYTES, 10_000, 1);

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> tool.extractWebPageText("https://example.com"));
        assertTrue(entered.await(5, TimeUnit.SECONDS), "first parse started");

        var refused = assertThrows(ToolFailureException.class, () -> tool.extractWebPageText("https://example.com"));
        assertTrue(refused.getMessage().contains("busy"), refused.getMessage());

        release.countDown();
        assertEquals("done", first.get(5, TimeUnit.SECONDS));
    }
}
