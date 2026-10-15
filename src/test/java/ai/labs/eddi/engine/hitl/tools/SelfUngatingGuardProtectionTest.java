/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.engine.hitl.tools.SelfUngatingGuard.Protection;
import ai.labs.eddi.engine.hitl.tools.SelfUngatingGuard.StoredGateLookup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Security review 2026-10-15, finding L13: the guard recognised
 * self-modification only by the agent id in the URI and {@code toolApprovals}
 * in LLM-store bodies. The agent's workflows and extension resources, and other
 * agents' gates on the agent store, were open.
 */
@DisplayName("SelfUngatingGuard — own resources and other agents' gates")
class SelfUngatingGuardProtectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AGENT = "68f1c0ffee0000000000a9e7";
    private static final String OTHER = "aaaabbbbccccddddeeeeffff";
    private static final String WORKFLOW = "5f1c0f3a9d8e4b2a7c6d1e01";
    private static final String LLM = "5f1c0f3a9d8e4b2a7c6d1e02";
    private static final String UNRELATED = "5f1c0f3a9d8e4b2a7c6d1e99";
    private static final String GATE = "{\"toolApprovals\":{\"requireApproval\":[\"http:*\"]}}";

    private static Protection resolved(StoredGateLookup gates) {
        return new Protection(AGENT, Set.of(WORKFLOW, LLM), false, gates);
    }

    private static StoredGateLookup storedGate(String hitlConfigJson) {
        return id -> hitlConfigJson == null ? null : MAPPER.readTree(hitlConfigJson);
    }

    @Nested
    @DisplayName("writes to the resources the agent runs on")
    class OwnResources {

        @Test
        @DisplayName("a write to the agent's own workflow or LLM document is refused — the agent id appears nowhere in it")
        void ownResourcesAreRefused() {
            Protection p = resolved(null);
            assertEquals(SelfUngatingGuard.OWN_RESOURCE_REASON,
                    SelfUngatingGuard.refusal("PUT", "https://eddi/llmstore/llms/" + LLM + "?version=2", "{\"tasks\":[]}", p));
            assertEquals(SelfUngatingGuard.OWN_RESOURCE_REASON,
                    SelfUngatingGuard.refusal("PATCH", "https://eddi/workflowstore/workflows/" + WORKFLOW, "[]", p));
            assertEquals(SelfUngatingGuard.OWN_RESOURCE_REASON,
                    SelfUngatingGuard.refusal("DELETE", "https://eddi/langchainstore/langchains/" + LLM.toUpperCase(), null, p),
                    "the v5 path and upper-case id are the same document");
            assertEquals(SelfUngatingGuard.OWN_RESOURCE_REASON,
                    SelfUngatingGuard.refusalFromArguments("{\"uri\":\"eddi://ai.labs.llm/llmstore/llms/" + LLM + "\"}", p),
                    "the raw-argument fallback tests the same ids");
        }

        @Test
        @DisplayName("reads of own resources and writes to unrelated documents still run")
        void readsAndUnrelatedDocumentsRun() {
            Protection p = resolved(null);
            assertNull(SelfUngatingGuard.refusal("GET", "https://eddi/llmstore/llms/" + LLM, null, p));
            assertNull(SelfUngatingGuard.refusal("PUT", "https://eddi/llmstore/llms/" + UNRELATED, "{\"tasks\":[]}", p));
            assertNull(SelfUngatingGuard.refusalFromArguments("{\"q\":\"weather\"}", p));
        }

        @Test
        @DisplayName("when the resources could not be resolved, every configuration-store write is refused and nothing else is")
        void unresolvedRefusesEveryStoreWrite() {
            Protection p = new Protection(AGENT, Set.of(), true, null);
            assertEquals(SelfUngatingGuard.UNRESOLVED_RESOURCES_REASON,
                    SelfUngatingGuard.refusal("PUT", "https://eddi/llmstore/llms/" + UNRELATED, "{}", p));
            assertEquals(SelfUngatingGuard.UNRESOLVED_RESOURCES_REASON,
                    SelfUngatingGuard.refusal("POST", "https://eddi/rulestore/rulesets", "{}", p));
            assertEquals(SelfUngatingGuard.UNRESOLVED_RESOURCES_REASON,
                    SelfUngatingGuard.refusalFromArguments("{\"url\":\"https://eddi/apicallstore/apicalls/x\"}", p));
            assertNull(SelfUngatingGuard.refusal("POST", "https://api.example.com/orders", "{}", p), "not a configuration store");
            assertNull(SelfUngatingGuard.refusal("GET", "https://eddi/llmstore/llms/" + UNRELATED, null, p));
        }

        @Test
        @DisplayName("the agent-id-only protection behaves as the guard always did")
        void agentOnlyIsTheOldGuard() {
            assertEquals(SelfUngatingGuard.OWN_AGENT_REASON,
                    SelfUngatingGuard.refusal("PUT", "https://eddi/agentstore/agents/" + AGENT + "/updateResourceUri", "eddi://x", AGENT));
            assertNull(SelfUngatingGuard.refusal("PUT", "https://eddi/llmstore/llms/" + LLM, "{\"tasks\":[]}", AGENT));
            assertNull(SelfUngatingGuard.refusal("PUT", "https://eddi/llmstore/llms/" + LLM, "{\"tasks\":[]}", (Protection) null));
        }
    }

    @Nested
    @DisplayName("another agent's gate on the agent store")
    class OtherAgentsGate {

        private static final String TARGET = "https://eddi/agentstore/agents/" + OTHER + "?version=4";

        @Test
        @DisplayName("a full PUT that drops a stored gate is refused; one that keeps it byte-for-byte runs")
        void removalRefusedFaithfulCopyRuns() {
            Protection p = resolved(storedGate(GATE));
            assertEquals(SelfUngatingGuard.GATE_REMOVAL_REASON,
                    SelfUngatingGuard.refusal("PUT", TARGET, "{\"workflows\":[],\"description\":\"renamed\"}", p), "hitlConfig dropped");
            assertEquals(SelfUngatingGuard.GATE_REMOVAL_REASON,
                    SelfUngatingGuard.refusal("PUT", TARGET, "{\"workflows\":[],\"hitlConfig\":{\"approvalTimeout\":\"PT1H\"}}", p),
                    "toolApprovals dropped");
            assertEquals(SelfUngatingGuard.GATE_REMOVAL_REASON,
                    SelfUngatingGuard.refusal("PUT", TARGET, "{\"hitlConfig\":{\"toolApprovals\":{\"requireApproval\":[]}}}", p),
                    "toolApprovals loosened");
            assertNull(SelfUngatingGuard.refusal("PUT", TARGET, "{\"workflows\":[],\"description\":\"renamed\",\"hitlConfig\":" + GATE + "}", p));
        }

        @Test
        @DisplayName("an agent with no stored gate may be written without one, and given one only by a human")
        void noStoredGate() {
            Protection p = resolved(storedGate(null));
            assertNull(SelfUngatingGuard.refusal("PUT", TARGET, "{\"workflows\":[]}", p));
            assertNull(SelfUngatingGuard.refusal("PUT", TARGET, "{\"hitlConfig\":{\"approvalTimeout\":\"PT1H\"}}", p), "no gate either side");
            assertEquals(SelfUngatingGuard.GATE_REMOVAL_REASON,
                    SelfUngatingGuard.refusal("PUT", TARGET, "{\"hitlConfig\":" + GATE + "}", p), "adding a gate is a human's edit too");
        }

        @Test
        @DisplayName("a stored gate that cannot be read makes the write unverifiable")
        void lookupFailureRefuses() {
            Protection p = resolved(id -> {
                throw new IllegalStateException("store down");
            });
            assertEquals(SelfUngatingGuard.UNVERIFIABLE_REASON, SelfUngatingGuard.refusal("PUT", TARGET, "{\"workflows\":[]}", p));
        }

        @Test
        @DisplayName("without a lookup, a body that touches hitlConfig or toolApprovals at all is refused; one that does not runs")
        void withoutLookup() {
            Protection p = resolved(null);
            assertEquals(SelfUngatingGuard.GATE_REMOVAL_REASON,
                    SelfUngatingGuard.refusal("PUT", TARGET, "{\"hitlConfig\":{\"approvalTimeout\":\"PT1H\"}}", p));
            assertEquals(SelfUngatingGuard.GATE_REMOVAL_REASON,
                    SelfUngatingGuard.refusal("PUT", TARGET, "{\"x\":{\"toolApprovals\":{}}}", p));
            assertNull(SelfUngatingGuard.refusal("PUT", TARGET, "{\"workflows\":[],\"description\":\"renamed\"}", p));
            assertNull(SelfUngatingGuard.refusal("PUT", "https://eddi/agentstore/agents/" + OTHER + "/updateResourceUri", "eddi://x", p),
                    "a resource URI body carries no gate");
        }

        @Test
        @DisplayName("a PATCH naming hitlConfig or toolApprovals, in path, from or value, is refused; an ordinary patch runs")
        void patches() {
            Protection p = resolved(storedGate(GATE));
            assertEquals(SelfUngatingGuard.GATE_REMOVAL_REASON,
                    SelfUngatingGuard.refusal("PATCH", TARGET, "[{\"op\":\"remove\",\"path\":\"/hitlConfig\"}]", p));
            assertEquals(SelfUngatingGuard.GATE_REMOVAL_REASON,
                    SelfUngatingGuard.refusal("PATCH", TARGET, "[{\"op\":\"replace\",\"path\":\"/hitlconfig/toolapprovals\",\"value\":{}}]", p));
            assertEquals(SelfUngatingGuard.GATE_REMOVAL_REASON,
                    SelfUngatingGuard.refusal("PATCH", TARGET, "[{\"op\":\"add\",\"path\":\"/x\",\"value\":{\"toolApprovals\":{}}}]", p));
            assertEquals(SelfUngatingGuard.GATE_REMOVAL_REASON,
                    SelfUngatingGuard.refusal("PATCH", TARGET, "[{\"op\":\"move\",\"from\":\"/hitlConfig\",\"path\":\"/y\"}]", p));
            assertEquals(SelfUngatingGuard.PATCH_OP_REASON,
                    SelfUngatingGuard.refusal("PATCH", TARGET, "[{\"op\":\"copy\",\"from\":\"/a\",\"path\":\"/b\"}]", p));
            assertNull(SelfUngatingGuard.refusal("PATCH", TARGET, "[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"x\"}]", p));
        }

        @Test
        @DisplayName("the v5 agent path is the same store, and a body that is not JSON carries no gate unless it names one")
        void legacyPathAndBadBody() {
            Protection p = resolved(storedGate(GATE));
            assertEquals(SelfUngatingGuard.GATE_REMOVAL_REASON,
                    SelfUngatingGuard.refusal("PUT", "https://eddi/botstore/bots/" + OTHER, "{\"workflows\":[]}", p));
            assertNull(SelfUngatingGuard.refusal("PUT", TARGET, "{not json", p), "not JSON, so no gate in it");
            assertEquals(SelfUngatingGuard.GATE_REMOVAL_REASON, SelfUngatingGuard.refusal("PUT", TARGET, "{not json hitlConfig", p));
        }

        @Test
        @DisplayName("a PostgreSQL UUID agent id is a target too — the gate is not MongoDB-only")
        void uuidTargetIsGuarded() {
            String uuid = "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
            Protection p = resolved(storedGate(GATE));
            assertEquals(SelfUngatingGuard.GATE_REMOVAL_REASON,
                    SelfUngatingGuard.refusal("PUT", "https://eddi/agentstore/agents/" + uuid + "?version=1", "{\"workflows\":[]}", p));
            assertEquals(SelfUngatingGuard.GATE_REMOVAL_REASON,
                    SelfUngatingGuard.refusal("PATCH", "https://eddi/agentstore/agents/" + uuid.toUpperCase(),
                            "[{\"op\":\"remove\",\"path\":\"/hitlConfig\"}]", p));
            // Own-resource ids in UUID shape are substring-tested like any other.
            Protection own = new Protection(AGENT, Set.of(uuid), false, null);
            assertEquals(SelfUngatingGuard.OWN_RESOURCE_REASON,
                    SelfUngatingGuard.refusal("PUT", "https://eddi/llmstore/llms/" + uuid, "{\"tasks\":[]}", own));
        }

        @Test
        @DisplayName("the raw-argument fallback refuses an argument list naming hitlConfig")
        void rawArguments() {
            assertEquals(SelfUngatingGuard.GATE_REMOVAL_REASON,
                    SelfUngatingGuard.refusalFromArguments("{\"agentId\":\"" + OTHER + "\",\"body\":{\"hitlConfig\":null}}", resolved(null)));
        }

        @Test
        @DisplayName("the stored gate is read for the TARGET agent, not the acting one")
        void lookupReceivesTargetId() {
            var seen = new String[1];
            StoredGateLookup gates = id -> {
                seen[0] = id;
                return (JsonNode) null;
            };
            SelfUngatingGuard.refusal("PUT", TARGET, "{\"workflows\":[]}", resolved(gates));
            assertEquals(OTHER, seen[0]);
        }
    }

    @Test
    @DisplayName("the LLM-store rule is unchanged under a full protection")
    void llmStoreRuleUnchanged() {
        Protection p = resolved(storedGate(GATE));
        assertEquals(SelfUngatingGuard.GATE_WRITE_REASON,
                SelfUngatingGuard.refusal("PUT", "https://eddi/llmstore/llms/" + UNRELATED, "{\"tasks\":[" + GATE + "]}", p));
        assertNull(SelfUngatingGuard.refusal("PUT", "https://eddi/llmstore/llms/" + UNRELATED, MAPPER.valueToTree(Map.of("tasks", 1)).toString(), p));
    }
}
