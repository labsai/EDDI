/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig.ApprovalRule;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-endpoint approval friction: {@code toolApprovals.rules}. The properties
 * under test are that a rule reaches the call the designer aimed it at, that
 * the most specific one wins regardless of list order, that a batch is governed
 * by its strictest rule, and — the load-bearing one — that a rule can never
 * change <em>whether</em> a call is gated.
 */
class ToolApprovalRulesTest {

    private static final Map<String, String> SOURCES = Map.of(
            "listAgents", "http", "createAgent", "http", "deployAgent", "http", "deleteAgent", "http", "readMemory", "memory");
    private static final Map<String, String> ENDPOINTS = Map.of(
            "listAgents", "get:/agentstore/agents/descriptors",
            "createAgent", "post:/agentstore/agents",
            "deployAgent", "post:/administration/{environment}/deploy/{agentId}",
            "deleteAgent", "delete:/agentstore/agents/{id}");

    private static ToolExecutionRequest req(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    private static ApprovalRule rule(String match, HitlTimeoutPolicy policy, String timeout) {
        var r = new ApprovalRule();
        r.setMatch(match);
        r.setTimeoutPolicy(policy);
        r.setApprovalTimeout(timeout);
        return r;
    }

    private static ToolApprovalsConfig cfg(ApprovalRule... rules) {
        var c = new ToolApprovalsConfig();
        c.setRequireApproval(List.of("http.post:*", "http.put:*", "http.patch:*", "http.delete:*"));
        c.setExempt(List.of("http.get:*"));
        c.setRules(List.of(rules));
        return c;
    }

    // ==================== matching ====================

    @Test
    void endpointAddressedRule_reachesTheGeneratedToolItNames() {
        // The whole point of the feature: "creating an agent" and "deploying one"
        // must be able to differ, and the tool names (operationId-derived) say
        // nothing, so the rule has to address the endpoint.
        var create = rule("http.post:/agentstore/agents", HitlTimeoutPolicy.WAIT_INDEFINITELY, null);
        var deploy = rule("http.post:/administration/{environment}/deploy/{agentId}", HitlTimeoutPolicy.AUTO_REJECT, "PT5M");

        var matched = ToolApprovalRules.matchByCallId(List.of(req("1", "createAgent")), SOURCES, ENDPOINTS, cfg(create, deploy));

        assertEquals(1, matched.size());
        assertEquals("http.post:/agentstore/agents", matched.get("1").getMatch());
    }

    @Test
    void ruleAddressesBareNameAndSourceQualifiedName_notOnlyEndpoints() {
        var byName = rule("readMemory", HitlTimeoutPolicy.AUTO_REJECT, "PT1M");
        var bySource = rule("memory:*", HitlTimeoutPolicy.ABORT, "PT1M");

        assertEquals("readMemory",
                ToolApprovalRules.matchByCallId(List.of(req("1", "readMemory")), SOURCES, ENDPOINTS, cfg(byName)).get("1").getMatch());
        assertEquals("memory:*",
                ToolApprovalRules.matchByCallId(List.of(req("1", "readMemory")), SOURCES, ENDPOINTS, cfg(bySource)).get("1").getMatch());
    }

    @Test
    void mostSpecificRuleWins_regardlessOfListOrder() {
        var broad = rule("http.post:*", HitlTimeoutPolicy.AUTO_REJECT, "PT5M");
        var narrow = rule("http.post:/agentstore/agents", HitlTimeoutPolicy.WAIT_INDEFINITELY, null);
        var call = List.of(req("1", "createAgent"));

        // Listed broad-first and narrow-first: the narrow rule governs either way, so
        // a designer cannot lose their intended friction to JSON array order.
        assertEquals("http.post:/agentstore/agents",
                ToolApprovalRules.matchByCallId(call, SOURCES, ENDPOINTS, cfg(broad, narrow)).get("1").getMatch());
        assertEquals("http.post:/agentstore/agents",
                ToolApprovalRules.matchByCallId(call, SOURCES, ENDPOINTS, cfg(narrow, broad)).get("1").getMatch());
    }

    @Test
    void aCallMatchingNoRule_isAbsentAndFallsBackToTheScalars() {
        var matched = ToolApprovalRules.matchByCallId(List.of(req("1", "createAgent")), SOURCES, ENDPOINTS,
                cfg(rule("http.delete:*", HitlTimeoutPolicy.WAIT_INDEFINITELY, null)));

        assertTrue(matched.isEmpty());
        assertNull(ToolApprovalRules.governing(matched.values()));
    }

    @Test
    void absentRulesList_resolvesToNothing() {
        var noRules = new ToolApprovalsConfig();
        noRules.setRequireApproval(List.of("http.post:*"));

        assertTrue(ToolApprovalRules.matchByCallId(List.of(req("1", "createAgent")), SOURCES, ENDPOINTS, noRules).isEmpty());
        assertNull(ToolApprovalRules.governing(List.of(req("1", "createAgent")), SOURCES, ENDPOINTS, noRules));
        assertNull(ToolApprovalRules.governing(List.of(req("1", "createAgent")), SOURCES, ENDPOINTS, null));
    }

    // ==================== batch reduction ====================

    @Test
    void strictestRuleGovernsTheBatch_soALenientCallCannotSoftenAStrictOne() {
        // A model can bundle a delete with a deploy; they pause together under ONE
        // policy. If the lenient one won, "delete waits for a human" would silently
        // become "delete auto-rejects in five minutes" — or worse, auto-approves.
        var deploy = rule("http.post:/administration/{environment}/deploy/{agentId}", HitlTimeoutPolicy.AUTO_REJECT, "PT5M");
        var delete = rule("http.delete:*", HitlTimeoutPolicy.WAIT_INDEFINITELY, null);
        var batch = List.of(req("1", "deployAgent"), req("2", "deleteAgent"));

        var governing = ToolApprovalRules.governing(batch, SOURCES, ENDPOINTS, cfg(deploy, delete));

        assertNotNull(governing);
        assertEquals("http.delete:*", governing.getMatch());
        assertEquals(HitlTimeoutPolicy.WAIT_INDEFINITELY, governing.getTimeoutPolicy());
    }

    @Test
    void autoApproveNeverWinsABatch_evenWhenItIsTheMoreSpecificRule() {
        // Specificity decides between equals; it must not promote the one policy that
        // can execute a write with nobody watching.
        var autoApprove = rule("http.post:/agentstore/agents", HitlTimeoutPolicy.AUTO_APPROVE, "PT1M");
        var broadWait = rule("http.post:*", HitlTimeoutPolicy.WAIT_INDEFINITELY, null);
        var batch = List.of(req("1", "createAgent"), req("2", "deployAgent"));

        var governing = ToolApprovalRules.governing(batch, SOURCES, ENDPOINTS, cfg(autoApprove, broadWait));

        assertEquals(HitlTimeoutPolicy.WAIT_INDEFINITELY, governing.getTimeoutPolicy());
    }

    @Test
    void aRuleStatingNoPolicy_yieldsToOneThatDoes() {
        var messageOnly = new ApprovalRule();
        messageOnly.setMatch("http.post:/agentstore/agents");
        messageOnly.setPauseReason("Creating a new agent — review the whole config");
        var deploy = rule("http.post:/administration/{environment}/deploy/{agentId}", HitlTimeoutPolicy.AUTO_REJECT, "PT5M");

        var governing = ToolApprovalRules.governing(List.of(req("1", "createAgent"), req("2", "deployAgent")),
                SOURCES, ENDPOINTS, cfg(messageOnly, deploy));

        assertEquals(HitlTimeoutPolicy.AUTO_REJECT, governing.getTimeoutPolicy());
    }

    @Test
    void withNoPolicyAnywhere_theMostSpecificRuleStillSuppliesTheMessages() {
        var narrow = new ApprovalRule();
        narrow.setMatch("http.post:/agentstore/agents");
        narrow.setPauseReason("narrow");
        var broad = new ApprovalRule();
        broad.setMatch("http.post:*");
        broad.setPauseReason("broad");

        var governing = ToolApprovalRules.governing(List.of(req("1", "createAgent")), SOURCES, ENDPOINTS, cfg(broad, narrow));

        assertEquals("narrow", governing.getPauseReason());
    }

    // ==================== the invariant ====================

    @Test
    void rulesNeverGateAnUngatedCall_norUngateAGatedOne() {
        // Rules tune friction only. If a rule could gate, a config would grant
        // capability by adding an entry; if it could ungate, one entry would remove
        // the review the whole design rests on. Assert against the gate itself.
        var config = cfg(
                rule("http.get:/agentstore/agents/descriptors", HitlTimeoutPolicy.WAIT_INDEFINITELY, null),
                rule("http.post:/agentstore/agents", HitlTimeoutPolicy.AUTO_APPROVE, "PT1M"));
        var batch = List.of(req("1", "listAgents"), req("2", "createAgent"));

        var result = new ToolApprovalGate().classify(batch, SOURCES, ENDPOINTS, config, Set.of());

        // The exempt GET stays allowed although a rule names it; the required POST
        // stays gated although its rule is the most permissive policy there is.
        assertEquals(List.of("listAgents"), result.allowed().stream().map(ToolExecutionRequest::name).toList());
        assertEquals(List.of("createAgent"), result.gated().stream().map(ToolExecutionRequest::name).toList());
    }

    @Test
    void aCallWithNoId_isSkippedRatherThanCollidingInTheMap() {
        var matched = ToolApprovalRules.matchByCallId(
                List.of(ToolExecutionRequest.builder().name("createAgent").arguments("{}").build()),
                SOURCES, ENDPOINTS, cfg(rule("http.post:*", HitlTimeoutPolicy.WAIT_INDEFINITELY, null)));

        assertTrue(matched.isEmpty());
    }

    @Test
    void aMalformedCallWithNoName_matchesNothing() {
        var matched = ToolApprovalRules.matchByCallId(
                List.of(ToolExecutionRequest.builder().id("1").arguments("{}").build()),
                SOURCES, ENDPOINTS, cfg(rule("*", HitlTimeoutPolicy.WAIT_INDEFINITELY, null)));

        assertTrue(matched.isEmpty());
    }
}
