/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.TestOutbound;
import ai.labs.eddi.secrets.SecretResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static ai.labs.eddi.modules.llm.tools.impl.ToolTestResults.textOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provider selection, the Brave Search API provider, and the startup warnings
 * of {@link WebSearchTool}. HTTP is mocked at {@link OutboundHttp}.
 */
class WebSearchToolProviderTest {

    private static final String BRAVE_BODY = """
            {
              "type": "search",
              "web": {
                "results": [
                  {"title": "EDDI <strong>agents</strong>", "url": "https://eddi.technology/",
                   "description": "Config-driven <strong>multi-agent</strong> middleware &amp; more"},
                  {"title": "Second", "url": "https://example.com/2", "description": "Two"},
                  {"title": "Third", "url": "https://example.com/3", "description": "Three"}
                ]
              }
            }
            """;

    private OutboundHttp client;
    private SecretResolver secretResolver;
    private WebSearchTool tool;

    @BeforeEach
    void setUp() {
        client = mock(OutboundHttp.class);
        secretResolver = mock(SecretResolver.class);
        tool = new WebSearchTool(client, new ObjectMapper(), secretResolver);
    }

    private void answer(int status, String body) throws Exception {
        doReturn(TestOutbound.response(status, body.getBytes(StandardCharsets.UTF_8), false)).when(client)
                .send(any(OutboundRequest.class), any());
    }

    private OutboundRequest sentRequest() throws Exception {
        ArgumentCaptor<OutboundRequest> request = ArgumentCaptor.forClass(OutboundRequest.class);
        verify(client).send(request.capture(), any());
        return request.getValue();
    }

    private void brave(String key) {
        tool.searchProvider = "brave";
        tool.braveApiKey = Optional.ofNullable(key);
    }

    @Nested
    class ProviderSelection {

        @Test
        void defaultIsDuckDuckGo() {
            assertEquals(WebSearchTool.PROVIDER_DUCKDUCKGO, tool.effectiveProvider());
        }

        @Test
        void braveWithKeyIsBrave_caseAndSpaceInsensitive() {
            brave("k");
            tool.searchProvider = " Brave ";
            assertEquals(WebSearchTool.PROVIDER_BRAVE, tool.effectiveProvider());
        }

        @Test
        void braveWithoutOrWithBlankKeyFallsBackToDuckDuckGo() {
            brave(null);
            assertEquals(WebSearchTool.PROVIDER_DUCKDUCKGO, tool.effectiveProvider());
            brave("  ");
            assertEquals(WebSearchTool.PROVIDER_DUCKDUCKGO, tool.effectiveProvider());
        }

        @Test
        void googleNeedsKeyAndCx() {
            tool.searchProvider = "google";
            tool.googleApiKey = Optional.of("k");
            assertEquals(WebSearchTool.PROVIDER_DUCKDUCKGO, tool.effectiveProvider());
            tool.googleCx = Optional.of("cx");
            assertEquals(WebSearchTool.PROVIDER_GOOGLE, tool.effectiveProvider());
        }

        @Test
        void unknownProviderIsDuckDuckGo() {
            tool.searchProvider = "bing";
            assertEquals(WebSearchTool.PROVIDER_DUCKDUCKGO, tool.effectiveProvider());
        }
    }

    @Nested
    class Brave {

        @Test
        void sendsKeyAsHeaderNeverInTheUrl() throws Exception {
            brave("brave-secret");
            answer(200, BRAVE_BODY);

            textOf(() -> tool.searchWeb("eddi agents & tools", 3));

            OutboundRequest request = sentRequest();
            String uri = request.uri().toString();
            assertTrue(uri.startsWith(WebSearchTool.BRAVE_ENDPOINT + "?q=eddi+agents+%26+tools&count=3"), uri);
            assertFalse(uri.contains("brave-secret"), uri);
            assertEquals(List.of("brave-secret"), request.headers().get("X-Subscription-Token"));
            assertEquals(List.of("application/json"), request.headers().get("accept"));
            assertEquals("GET", request.method());
        }

        @Test
        void resultsHaveTheGoogleShapeWithHtmlStripped() throws Exception {
            brave("k");
            answer(200, BRAVE_BODY);

            String result = textOf(() -> tool.searchWeb("eddi", 5));

            assertTrue(result.startsWith("Search results for 'eddi':\n\n"), result);
            assertTrue(result.contains("1. EDDI agents\n   Config-driven multi-agent middleware & more\n   https://eddi.technology/\n\n"),
                    result);
            assertTrue(result.contains("3. Third"), result);
            assertFalse(result.contains("<strong>"), result);
        }

        @Test
        void maxResultsCapsTheListedResults() throws Exception {
            brave("k");
            answer(200, BRAVE_BODY);

            String result = textOf(() -> tool.searchWeb("eddi", 2));

            assertTrue(result.contains("2. Second"), result);
            assertFalse(result.contains("3. Third"), result);
        }

        @Test
        void overlongQueryIsCutToBravesLimit() throws Exception {
            brave("k");
            answer(200, "{}");

            textOf(() -> tool.searchWeb("a".repeat(WebSearchTool.BRAVE_MAX_QUERY_LENGTH + 50), 5));

            String uri = sentRequest().uri().toString();
            assertTrue(uri.contains("q=" + "a".repeat(WebSearchTool.BRAVE_MAX_QUERY_LENGTH) + "&count="), uri);
        }

        @Test
        void noWebResultsReportsNoResults() throws Exception {
            brave("k");
            answer(200, "{\"type\":\"search\",\"query\":{\"original\":\"zzz\"}}");

            String result = textOf(() -> tool.searchWeb("zzz", 5));

            assertTrue(result.contains("No results found for 'zzz'."), result);
        }

        @Test
        void non200IsAToolFailure() throws Exception {
            brave("k");
            answer(429, "{\"type\":\"ErrorResponse\"}");

            String result = textOf(() -> tool.searchWeb("q", 5));

            assertTrue(result.contains("Error:"), result);
            assertTrue(result.contains("Brave search returned status: 429"), result);
        }

        @Test
        void vaultReferenceIsResolvedBeforeSending() throws Exception {
            brave("${vault:brave-search}");
            when(secretResolver.resolveValue("${vault:brave-search}")).thenReturn("from-vault");
            answer(200, BRAVE_BODY);

            textOf(() -> tool.searchWeb("q", 5));

            assertEquals(List.of("from-vault"), sentRequest().headers().get("X-Subscription-Token"));
        }

        @Test
        void unexpandedVaultReferenceWinsOverWhatConfigExpansionLeftInTheField() throws Exception {
            // Config expands ${vault:brave-search} to its "default", brave-search.
            WebSearchTool expanded = new WebSearchTool(client, new ObjectMapper(), secretResolver) {
                @Override
                Optional<String> rawBraveKey() {
                    return Optional.of("${vault:brave-search}");
                }
            };
            expanded.searchProvider = "brave";
            expanded.braveApiKey = Optional.of("brave-search");
            when(secretResolver.resolveValue("${vault:brave-search}")).thenReturn("from-vault");
            answer(200, BRAVE_BODY);

            textOf(() -> expanded.searchWeb("q", 5));

            assertEquals(List.of("from-vault"), sentRequest().headers().get("X-Subscription-Token"));
        }

        @Test
        void plainRawKeyDoesNotOverrideTheInjectedOne() throws Exception {
            WebSearchTool plain = new WebSearchTool(client, new ObjectMapper(), secretResolver) {
                @Override
                Optional<String> rawBraveKey() {
                    return Optional.of("${BRAVE_KEY}");
                }
            };
            plain.searchProvider = "brave";
            plain.braveApiKey = Optional.of("expanded-key");
            answer(200, BRAVE_BODY);

            textOf(() -> plain.searchWeb("q", 5));

            assertEquals(List.of("expanded-key"), sentRequest().headers().get("X-Subscription-Token"));
        }

        @Test
        void unresolvedVaultReferenceIsRefusedAndNeverSent() throws Exception {
            brave("${vault:brave-search}");
            when(secretResolver.resolveValue("${vault:brave-search}")).thenReturn("${vault:brave-search}");

            String result = textOf(() -> tool.searchWeb("q", 5));

            assertTrue(result.contains("could not be resolved"), result);
            verify(client, never()).send(any(OutboundRequest.class), any());
        }

        @Test
        void vaultReferenceWithoutAResolverIsRefused() throws Exception {
            WebSearchTool standalone = new WebSearchTool(client, new ObjectMapper());
            standalone.searchProvider = "brave";
            standalone.braveApiKey = Optional.of("${vault:brave-search}");

            String result = textOf(() -> standalone.searchWeb("q", 5));

            assertTrue(result.contains("could not be resolved"), result);
            verify(client, never()).send(any(OutboundRequest.class), any());
        }

        @Test
        void plainTextDecodesEntitiesOnce() {
            assertEquals("a & <b> \"c\" 'd' &lt;", WebSearchTool.plainText("<em>a</em> &amp; &lt;b&gt; &quot;c&quot; &#x27;d&#39; &amp;lt;"));
        }
    }

    @Nested
    class StartupWarnings {

        private final LocalDate beforeShutdown = LocalDate.of(2026, 11, 9);

        @Test
        void duckDuckGoHasNone() {
            assertEquals(List.of(), tool.configurationWarnings(beforeShutdown));
        }

        @Test
        void braveWithKeyHasNone() {
            brave("k");
            assertEquals(List.of(), tool.configurationWarnings(beforeShutdown));
        }

        @Test
        void braveWithoutKeyNamesTheProperty() {
            brave(null);
            List<String> warnings = tool.configurationWarnings(beforeShutdown);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("eddi.tools.websearch.brave.api-key is not set"), warnings.get(0));
        }

        @Test
        void googleNamesTheShutdownDateAndTheReplacement() {
            tool.searchProvider = "google";
            tool.googleApiKey = Optional.of("k");
            tool.googleCx = Optional.of("cx");

            List<String> warnings = tool.configurationWarnings(beforeShutdown);

            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("shuts down on 2027-01-01"), warnings.get(0));
            assertTrue(warnings.get(0).contains("eddi.tools.websearch.provider=brave"), warnings.get(0));
        }

        @Test
        void googleAfterTheShutdownSaysItWasShutDown() {
            tool.searchProvider = "google";
            tool.googleApiKey = Optional.of("k");
            tool.googleCx = Optional.of("cx");

            List<String> warnings = tool.configurationWarnings(LocalDate.of(2027, 1, 1));

            assertTrue(warnings.get(0).contains("was shut down on 2027-01-01"), warnings.get(0));
        }

        @Test
        void googleWithoutCredentialsAlsoWarnsOfTheFallback() {
            tool.searchProvider = "google";

            List<String> warnings = tool.configurationWarnings(beforeShutdown);

            assertEquals(2, warnings.size(), warnings.toString());
            assertTrue(warnings.get(1).contains("go to DuckDuckGo instead"), warnings.get(1));
        }

        @Test
        void unknownProviderIsNamed() {
            tool.searchProvider = "bing";

            List<String> warnings = tool.configurationWarnings(beforeShutdown);

            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("provider=bing is not a known provider"), warnings.get(0));
        }
    }
}
