/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.compat;

import ai.labs.eddi.engine.compat.CompatibilityRules.Finding;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CompatibilityRules - what the deploy-time lint reports")
class CompatibilityRulesTest {

    private static final String LOC = "llm task 'chat'";

    private static LlmConfiguration.Task task(String type, Map<String, String> parameters) {
        var task = new LlmConfiguration.Task();
        task.setType(type);
        task.setParameters(new HashMap<>(parameters));
        return task;
    }

    private static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::code).toList();
    }

    private static List<Finding> lint(LlmConfiguration.Task task, boolean apiCalls, boolean mcpCalls) {
        return CompatibilityRules.lintLlmTask(LOC, task, apiCalls, mcpCalls);
    }

    // ---- #6 apiKey ----

    @Test
    @DisplayName("an apiKey template is flagged")
    void apiKeyTemplateIsFlagged() {
        var findings = lint(task("gemini", Map.of("apiKey", "{properties.geminiToken}")), false, false);

        assertEquals(List.of(CompatibilityRules.LLM_API_KEY_TEMPLATE), codes(findings));
        assertTrue(findings.get(0).render().contains("vault"));
    }

    @Test
    @DisplayName("vault, vars and connection references and literal keys are not templates")
    void referencesAreNotFlagged() {
        for (String key : List.of("${vault:gemini-api-key}", "${eddivault:k}", "${vars:default-key}", "${vault:tenant/key}", "sk-abc123")) {
            assertEquals(List.of(), codes(lint(task("gemini", Map.of("apiKey", key)), false, false)), key);
        }
    }

    @Test
    @DisplayName("a template mixed with a vault reference is still flagged")
    void mixedIsFlagged() {
        assertEquals(List.of(CompatibilityRules.LLM_API_KEY_TEMPLATE),
                codes(lint(task("gemini", Map.of("apiKey", "${vault:a}{properties.suffix}")), false, false)));
    }

    @Test
    @DisplayName("cascade step and judge apiKeys are checked too")
    void cascadeStepsAreChecked() {
        var task = task("openai", Map.of("apiKey", "${vault:ok}"));
        var cascade = new LlmConfiguration.ModelCascadeConfig();
        var step = new LlmConfiguration.CascadeStep();
        step.setType("openai");
        step.setParameters(Map.of("apiKey", "{properties.k}"));
        cascade.setSteps(List.of(step));
        var judge = new LlmConfiguration.JudgeModelConfig();
        judge.setParameters(Map.of("apiKey", "{#if x}a{/if}"));
        cascade.setJudgeModel(judge);
        task.setModelCascade(cascade);

        var findings = lint(task, false, false);

        assertEquals(2, findings.size());
        assertTrue(findings.stream().anyMatch(f -> f.location().contains("cascade step 0")));
        assertTrue(findings.stream().anyMatch(f -> f.location().contains("judge model")));
    }

    // ---- #7 responseFormat ----

    @Test
    @DisplayName("responseFormat json without convertToObject is flagged, case-insensitively")
    void responseFormatWithoutConvert() {
        assertEquals(List.of(CompatibilityRules.LLM_RESPONSE_FORMAT_WITHOUT_CONVERT),
                codes(lint(task("gemini", Map.of("responseFormat", "JSON")), false, false)));
    }

    @Test
    @DisplayName("responseFormat json with convertToObject true is fine")
    void responseFormatWithConvert() {
        assertEquals(List.of(), codes(lint(task("gemini", Map.of("responseFormat", "json", "convertToObject", "true")), false, false)));
    }

    // ---- #8 JSON mode with tools ----

    @Test
    @DisplayName("convertToObject with tools on a provider that cannot combine them is flagged")
    void jsonWithToolsOnGemini() {
        var findings = lint(task("gemini", Map.of("convertToObject", "true")), true, false);

        assertEquals(List.of(CompatibilityRules.LLM_JSON_MODE_WITH_TOOLS), codes(findings));
    }

    @Test
    @DisplayName("not flagged: provider that supports both, tools switched off, no tool sources, or jsonResponseFormat on")
    void jsonWithToolsNotFlagged() {
        assertEquals(List.of(), codes(lint(task("openai", Map.of("convertToObject", "true")), true, true)), "openai supports both");

        var off = task("gemini", Map.of("convertToObject", "true"));
        off.setEnableHttpCallTools(false);
        off.setEnableMcpCallTools(false);
        assertEquals(List.of(), codes(lint(off, true, true)), "tools off");

        assertEquals(List.of(), codes(lint(task("gemini", Map.of("convertToObject", "true")), false, false)), "no api/mcp calls in the agent");

        var forced = task("gemini", Map.of("convertToObject", "true"));
        forced.setJsonResponseFormat("on");
        assertEquals(List.of(), codes(lint(forced, true, false)), "explicit override");
    }

    @Test
    @DisplayName("agent mode counts as tools even without API calls")
    void agentModeCountsAsTools() {
        var task = task("gemini", Map.of("convertToObject", "true"));
        task.setEnableBuiltInTools(true);

        assertEquals(List.of(CompatibilityRules.LLM_JSON_MODE_WITH_TOOLS), codes(lint(task, false, false)));
    }

    // ---- templates ----

    private static List<Finding> scan(Object node) {
        var engine = new TemplatingEngine(Engine.builder().addDefaults().build());
        var findings = new ArrayList<Finding>();
        CompatibilityRules.scanTemplates("doc", node, engine::validateTemplate, findings);
        return findings;
    }

    @Test
    @DisplayName("a template that does not parse is reported with its path; valid and plain text are not")
    void unparsableTemplate() {
        // the shape V6QuteMigration used to write for a nested ternary
        String broken = "{userInfo.n}{userInfo.m : ' overview screen') : 'program overview screen')}";
        var node = Map.of("setOnActions", List.of(Map.of("value", broken, "other", "{properties.name} and {#if a}b{/if}", "plain", "hello")));

        var findings = scan(node);

        assertEquals(1, findings.size(), findings.toString());
        assertEquals(CompatibilityRules.TEMPLATE_DOES_NOT_PARSE, findings.get(0).code());
        assertEquals("doc.setOnActions[0].value", findings.get(0).location());
    }

    @Test
    @DisplayName("left-over Thymeleaf syntax is reported")
    void thymeleafLeftover() {
        var findings = scan(Map.of("text", "Hello [[${userInfo.name}]]"));

        assertEquals(List.of(CompatibilityRules.THYMELEAF_NOT_CONVERTED), codes(findings));
    }

    @Test
    @DisplayName("credential parameters are never scanned")
    void credentialsSkipped() {
        assertEquals(List.of(), scan(Map.of("parameters", Map.of("apiKey", "{userInfo.m : 'x') : 'y')}"))));
    }
}
