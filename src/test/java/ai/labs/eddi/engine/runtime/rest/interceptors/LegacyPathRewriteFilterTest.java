/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.rest.interceptors;

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
        @DisplayName("/bottriggerstore/bottriggers → /agenttriggerstore/agenttriggers")
        void botTriggerToAgentTrigger() {
            assertEquals("/agenttriggerstore/agenttriggers/t1",
                    LegacyPathRewriteFilter.rewritePath("/bottriggerstore/bottriggers/t1"));
        }

        @Test
        @DisplayName("/langchain/tools → /llm/tools")
        void langchainToolsToLlmTools() {
            assertEquals("/llm/tools",
                    LegacyPathRewriteFilter.rewritePath("/langchain/tools"));
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
