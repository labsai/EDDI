/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.hitl.ApprovalContextValidation.Scope;
import ai.labs.eddi.configs.hitl.model.ApprovalContextBlock;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ApprovalContextValidation")
class ApprovalContextValidationTest {

    private static ApprovalContextBlock block(String label, String template) {
        return new ApprovalContextBlock(label, template, null);
    }

    private static List<ApprovalContextBlock> blocks(int n) {
        List<ApprovalContextBlock> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(block("L" + i, "{properties.x" + i + "}"));
        }
        return list;
    }

    private static String refusal(List<ApprovalContextBlock> blocks, Scope scope) {
        return assertThrows(IllegalArgumentException.class, () -> ApprovalContextValidation.validate(blocks, "path", scope)).getMessage();
    }

    @Test
    void absentAndEmptyAreNoOps() {
        assertDoesNotThrow(() -> ApprovalContextValidation.validate(null, "path", Scope.AGENT));
        assertDoesNotThrow(() -> ApprovalContextValidation.validate(List.of(), "path", Scope.AGENT));
    }

    @Test
    void blockCountBoundaries() {
        assertDoesNotThrow(() -> ApprovalContextValidation.validate(blocks(8), "path", Scope.AGENT));
        assertTrue(refusal(blocks(9), Scope.AGENT).contains("at most 8"));
        assertDoesNotThrow(() -> ApprovalContextValidation.validate(blocks(4), "path", Scope.RULE));
        assertTrue(refusal(blocks(5), Scope.RULE).contains("at most 4"));
        assertDoesNotThrow(() -> ApprovalContextValidation.validate(blocks(8), "path", Scope.GROUP));
        assertTrue(refusal(blocks(9), Scope.GROUP).contains("at most 8"));
    }

    @Test
    void labelBoundaries() {
        assertDoesNotThrow(() -> ApprovalContextValidation.validate(List.of(block("x".repeat(80), "{a}")), "path", Scope.AGENT));
        assertTrue(refusal(List.of(block("x".repeat(81), "{a}")), Scope.AGENT).contains("path[0].label"));
        assertTrue(refusal(List.of(block(" ", "{a}")), Scope.AGENT).contains("label must not be blank"));
        assertTrue(refusal(List.of(block(null, "{a}")), Scope.AGENT).contains("label must not be blank"));
    }

    @Test
    void templateBoundaries() {
        assertDoesNotThrow(() -> ApprovalContextValidation.validate(List.of(block("L", "x".repeat(4000))), "path", Scope.AGENT));
        assertTrue(refusal(List.of(block("L", "x".repeat(4001))), Scope.AGENT).contains("path[0].template exceeds"));
        assertTrue(refusal(List.of(block("L", "  ")), Scope.AGENT).contains("template must not be blank"));
        assertTrue(refusal(java.util.Collections.singletonList(null), Scope.AGENT).contains("must not be null"));
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 51, 1000, 2899, 2900})
    void maxCharsInRangeAccepted(int maxChars) {
        assertDoesNotThrow(
                () -> ApprovalContextValidation.validate(List.of(new ApprovalContextBlock("L", "{a}", maxChars)), "path", Scope.AGENT));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 49, 2901, 100000})
    void maxCharsOutOfRangeRefused(int maxChars) {
        String message = refusal(List.of(new ApprovalContextBlock("L", "{a}", maxChars)), Scope.AGENT);
        assertTrue(message.contains("maxChars must be between 50 and 2900"), message);
    }

    @Test
    void parseErrorNamesTheBlockIndex() {
        String message = refusal(List.of(block("ok", "{a}"), block("bad", "{#if x}never closed")), Scope.AGENT);
        assertTrue(message.startsWith("path[1].template does not parse"), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{vault:secretName}", "{eddivault:x}", "{connection:github}", "{caller:token}",
            "{#if properties.a}{vault:k}{/if}", "{#for s in properties.l}{s.x} {caller:y}{/for}"})
    void secretAndConnectionNamespacesRefused(String template) {
        String message = refusal(List.of(block("L", template)), Scope.AGENT);
        assertTrue(message.contains("namespace"), message);
        assertTrue(message.contains("path[0].template"), message);
    }

    @Test
    void otherNamespacesAreFine() {
        assertDoesNotThrow(() -> ApprovalContextValidation.validate(
                List.of(block("L", "{vars:thing} {str:upper(properties.x)}"), block("M", "{json:encode(properties.y)}")), "path",
                Scope.AGENT));
    }

    @Test
    void callOutsideARuleIsRefusedInsideItIsFine() {
        List<ApprovalContextBlock> callBlock = List.of(block("L", "{call.args.text}"));
        assertTrue(refusal(callBlock, Scope.AGENT).contains("call.*"));
        assertTrue(refusal(callBlock, Scope.GROUP).contains("call.*"));
        assertTrue(refusal(List.of(block("L", "{#for x in call.args.items}{x}{/for}")), Scope.AGENT).contains("call.*"));
        assertDoesNotThrow(() -> ApprovalContextValidation.validate(callBlock, "path", Scope.RULE));
    }

    @Test
    void groupOutsideAGroupOnlyWarns() {
        List<ApprovalContextBlock> groupBlock = List.of(block("L", "{group.phase.name}"));
        assertDoesNotThrow(() -> ApprovalContextValidation.validate(groupBlock, "path", Scope.AGENT));
        assertDoesNotThrow(() -> ApprovalContextValidation.validate(groupBlock, "path", Scope.GROUP));
    }

    // ---- wired through HitlConfigValidation (what every store and the ZIP import
    // call) ----

    @Test
    void agentHitlConfigValidateRunsIt() {
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setApprovalContext(List.of(block("L", "{vault:x}")));
        var e = assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl));
        assertTrue(e.getMessage().startsWith("hitlConfig.approvalContext[0].template"), e.getMessage());
    }

    @Test
    void groupHitlConfigValidateRunsIt() {
        var hitl = new AgentGroupConfiguration.HitlConfig();
        hitl.setApprovalContext(blocks(9));
        var e = assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl));
        assertTrue(e.getMessage().startsWith("hitlConfig.approvalContext has 9 blocks"), e.getMessage());
    }

    @Test
    void ruleContextIsValidatedWithTheCallRootAllowed() {
        ToolApprovalsConfig cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("mcp:publish_*"));
        var rule = new ToolApprovalsConfig.ApprovalRule();
        rule.setMatch("mcp:publish_linkedin_post");
        rule.setContext(List.of(block("Post text", "{call.args.text}")));
        cfg.setRules(List.of(rule));
        assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(cfg, "hitlConfig.toolApprovals"));

        rule.setContext(List.of(block("Post text", "{connection:x}")));
        var e = assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validateToolApprovals(cfg, "hitlConfig.toolApprovals"));
        assertTrue(e.getMessage().startsWith("hitlConfig.toolApprovals.rules[0].context[0].template"), e.getMessage());
    }

    /**
     * The ZIP import deserializes the agent JSON and calls the same static
     * validate: prove the JSON shape round-trips into the model that is validated.
     */
    @Test
    void importedJsonShapeReachesTheValidator() throws Exception {
        String json = """
                {"hitlConfig":{"approvalContext":[{"label":"Draft","template":"{caller:token}","maxChars":1200}],
                 "toolApprovals":{"requireApproval":["mcp:publish_*"],
                   "rules":[{"match":"mcp:publish_linkedin_post","context":[{"label":"Post","template":"{call.args.text}"}]}]}}}
                """;
        AgentConfiguration agent = new JsonSerialization(new ObjectMapper()).deserialize(json, AgentConfiguration.class);
        assertEquals(1200, agent.getHitlConfig().getApprovalContext().get(0).getMaxChars());
        assertEquals("Post", agent.getHitlConfig().getToolApprovals().getRules().get(0).getContext().get(0).getLabel());
        var e = assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(agent.getHitlConfig()));
        assertTrue(e.getMessage().contains("'caller:' namespace"), e.getMessage());
    }

    @Test
    void absentConfigStaysAbsentThroughTheSerializer() throws Exception {
        var hitl = new AgentConfiguration.HitlConfig();
        String json = new ObjectMapper().writeValueAsString(hitl);
        var back = new ObjectMapper().readValue(json, AgentConfiguration.HitlConfig.class);
        assertNull(back.getApprovalContext());
    }
}
