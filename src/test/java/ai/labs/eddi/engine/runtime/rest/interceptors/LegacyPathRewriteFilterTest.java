/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.rest.interceptors;

import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LegacyPathRewriteFilter Tests")
class LegacyPathRewriteFilterTest {

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
        @DisplayName("/unrestricted/ → /production/")
        void unrestrictedSegmentBecomesProduction() {
            assertEquals("/agentstore/agents/abc/production/whatever",
                    LegacyPathRewriteFilter.rewritePath("/botstore/bots/abc/unrestricted/whatever"));
        }

        @Test
        @DisplayName("/restricted/ → /production/")
        void restrictedSegmentBecomesProduction() {
            assertEquals("/agents/abc/production/status",
                    LegacyPathRewriteFilter.rewritePath("/agents/abc/restricted/status"));
        }

        @Test
        @DisplayName("trailing /unrestricted → /production")
        void trailingUnrestrictedBecomesProduction() {
            assertEquals("/agents/abc/production",
                    LegacyPathRewriteFilter.rewritePath("/agents/abc/unrestricted"));
        }

        @Test
        @DisplayName("trailing /restricted → /production")
        void trailingRestrictedBecomesProduction() {
            assertEquals("/agents/abc/production",
                    LegacyPathRewriteFilter.rewritePath("/agents/abc/restricted"));
        }

        @Test
        @DisplayName("store rewrite and environment rewrite apply together")
        void storeAndEnvironmentRewriteCombine() {
            assertEquals("/agentstore/agents/abc/production",
                    LegacyPathRewriteFilter.rewritePath("/botstore/bots/abc/unrestricted"));
        }

        @Test
        @DisplayName("a v6 /production path is left untouched")
        void productionPathUnchanged() {
            String path = "/agentstore/agents/abc/production";
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
