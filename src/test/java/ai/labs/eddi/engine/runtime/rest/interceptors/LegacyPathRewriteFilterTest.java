/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.rest.interceptors;

import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LegacyPathRewriteFilter Tests")
class LegacyPathRewriteFilterTest {

    private static ContainerRequestContext request(String base, String path) {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo info = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(info);
        when(info.getBaseUri()).thenReturn(URI.create("http://host" + base));
        when(info.getRequestUri()).thenReturn(URI.create("http://host" + path + "?x=1"));
        return context;
    }

    @Test
    @DisplayName("filter: under the default root a legacy path is rewritten as before")
    void filterRewritesUnderTheDefaultRoot() {
        ContainerRequestContext context = request("/", "/botstore/bots/abc");
        new LegacyPathRewriteFilter().filter(context);
        verify(context).setRequestUri(eq(URI.create("http://host/")), eq(URI.create("/agentstore/agents/abc?x=1")));
    }

    @Test
    @DisplayName("filter: under quarkus.http.root-path the rules match below the root and the root is kept")
    void filterRewritesBelowARootPath() {
        ContainerRequestContext context = request("/eddi/", "/eddi/botstore/bots/abc");
        new LegacyPathRewriteFilter().filter(context);
        verify(context).setRequestUri(eq(URI.create("http://host/eddi/")), eq(URI.create("/eddi/agentstore/agents/abc?x=1")));
    }

    @Test
    @DisplayName("filter: a path that is not legacy is left alone, under a root as well")
    void filterLeavesCurrentPathsAlone() {
        ContainerRequestContext context = request("/eddi/", "/eddi/agentstore/agents/abc");
        new LegacyPathRewriteFilter().filter(context);
        verify(context, never()).setRequestUri(any(URI.class), any(URI.class));
    }

    @Nested
    @DisplayName("rewritePath — store path rewrites")
    class StorePathTests {

        @Test
        @DisplayName("/botstore/bots → /agentstore/agents")
        void botsToAgents() {
            assertEquals("/agentstore/agents/abc",
                    LegacyPathRewriteFilter.rewritePath("/botstore/bots/abc"));
        }

        @Test
        @DisplayName("/packagestore/packages → /workflowstore/workflows")
        void packagesToWorkflows() {
            assertEquals("/workflowstore/workflows/wf1",
                    LegacyPathRewriteFilter.rewritePath("/packagestore/packages/wf1"));
        }

        @Test
        @DisplayName("/langchainstore/langchains → /llmstore/llms")
        void langchainToLlm() {
            assertEquals("/llmstore/llms/llm1",
                    LegacyPathRewriteFilter.rewritePath("/langchainstore/langchains/llm1"));
        }

        @Test
        @DisplayName("/behaviorstore/behaviorsets → /rulestore/rulesets")
        void behaviorToRules() {
            assertEquals("/rulestore/rulesets/rs1",
                    LegacyPathRewriteFilter.rewritePath("/behaviorstore/behaviorsets/rs1"));
        }

        @Test
        @DisplayName("/httpcallsstore/httpcalls → /apicallstore/apicalls")
        void httpCallsToApiCalls() {
            assertEquals("/apicallstore/apicalls/ac1",
                    LegacyPathRewriteFilter.rewritePath("/httpcallsstore/httpcalls/ac1"));
        }

        @Test
        @DisplayName("/regulardictionarystore/regulardictionaries → /dictionarystore/dictionaries")
        void regularDictionaryToDictionary() {
            assertEquals("/dictionarystore/dictionaries/d1",
                    LegacyPathRewriteFilter.rewritePath("/regulardictionarystore/regulardictionaries/d1"));
        }

        @Test
        @DisplayName("/bottriggerstore/bottriggers → /AgentTriggerStore/agenttriggers (case must match the @Path)")
        void botTriggerToAgentTrigger() {
            assertEquals("/AgentTriggerStore/agenttriggers/t1",
                    LegacyPathRewriteFilter.rewritePath("/bottriggerstore/bottriggers/t1"));
        }

        @Test
        @DisplayName("rewritten trigger path matches the declared @Path of IRestAgentTriggerStore")
        void botTriggerRewriteMatchesDeclaredJaxRsPath() {
            String declaredPath = IRestAgentTriggerStore.class.getAnnotation(Path.class).value();

            assertEquals(declaredPath + "/t1",
                    LegacyPathRewriteFilter.rewritePath("/bottriggerstore/bottriggers/t1"),
                    "JAX-RS path matching is case-sensitive — a rewrite that does not match the declared @Path yields a 404");
        }

        @Test
        @DisplayName("/langchain/tools → /llm/tools")
        void langchainToolsToLlmTools() {
            assertEquals("/llm/tools",
                    LegacyPathRewriteFilter.rewritePath("/langchain/tools"));
        }
    }

    @Nested
    @DisplayName("rewritePath — legacy environment segments")
    class EnvironmentTests {

        @Test
        @DisplayName("/administration/unrestricted/… → /administration/production/…")
        void unrestrictedSegmentBecomesProduction() {
            assertEquals("/administration/production/deploy/abc",
                    LegacyPathRewriteFilter.rewritePath("/administration/unrestricted/deploy/abc"));
        }

        @Test
        @DisplayName("/administration/restricted/… → /administration/production/…")
        void restrictedSegmentBecomesProduction() {
            assertEquals("/administration/production/deploymentstatus",
                    LegacyPathRewriteFilter.rewritePath("/administration/restricted/deploymentstatus"));
        }

        /**
         * An id, key or user literally named restricted/unrestricted is not an
         * environment: rewriting it misdirected destructive operations (an erasure of
         * user "production", a secret key "production").
         */
        @Test
        @DisplayName("restricted/unrestricted outside the environment position are left alone")
        void unrelatedSegmentsAreNotRewritten() {
            for (String path : new String[]{"/admin/gdpr/restricted", "/secretstore/secrets/default/unrestricted",
                    "/agents/managed/restricted/user-1", "/agents/abc/restricted/status", "/administration/x/restricted"}) {
                assertEquals(path, LegacyPathRewriteFilter.rewritePath(path), path);
            }
        }

        @Test
        @DisplayName("a store prefix is rewritten only where the path starts with it")
        void storePrefixOnlyAtTheStart() {
            assertEquals("/agentstore/agents/abc/unrestricted", LegacyPathRewriteFilter.rewritePath("/botstore/bots/abc/unrestricted"));
            String nested = "/secretstore/secrets/default/botstore/bots";
            assertEquals(nested, LegacyPathRewriteFilter.rewritePath(nested));
            assertEquals("/botstore/botsx", LegacyPathRewriteFilter.rewritePath("/botstore/botsx"));
        }

        @Test
        @DisplayName("a v6 /production path is left untouched")
        void productionPathUnchanged() {
            String path = "/administration/production/deploy/abc";
            assertEquals(path, LegacyPathRewriteFilter.rewritePath(path));
        }
    }

    @Nested
    @DisplayName("rewritePath — no match")
    class NoMatchTests {

        @Test
        @DisplayName("modern path is unchanged")
        void modernPathUnchanged() {
            String path = "/agentstore/agents/abc";
            assertEquals(path, LegacyPathRewriteFilter.rewritePath(path));
        }

        @Test
        @DisplayName("root path is unchanged")
        void rootPath() {
            assertEquals("/", LegacyPathRewriteFilter.rewritePath("/"));
        }

        @Test
        @DisplayName("arbitrary path is unchanged")
        void arbitraryPath() {
            String path = "/api/v1/status";
            assertEquals(path, LegacyPathRewriteFilter.rewritePath(path));
        }
    }
}
