/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import ai.labs.eddi.modules.llm.model.ToolQuotaConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("toolQuotas save-time validation")
public class ToolQuotaConfigValidatorTest {

    public static ToolQuotaConfig.Rule rule(Integer limit, String window, String pool) {
        var rule = new ToolQuotaConfig.Rule();
        rule.setLimit(limit);
        rule.setWindow(window);
        rule.setPool(pool);
        return rule;
    }

    public static ToolQuotaConfig config(Object... nameAndRule) {
        var config = new ToolQuotaConfig();
        var tools = new LinkedHashMap<String, ToolQuotaConfig.Rule>();
        for (int i = 0; i < nameAndRule.length; i += 2) {
            tools.put((String) nameAndRule[i], (ToolQuotaConfig.Rule) nameAndRule[i + 1]);
        }
        config.setTools(tools);
        return config;
    }

    private static String rejection(ToolQuotaConfig config) {
        return assertThrows(IllegalArgumentException.class, () -> ToolQuotaConfigValidator.validate(config, "task.toolQuotas")).getMessage();
    }

    @Test
    @DisplayName("absent toolQuotas is valid")
    void absentIsValid() {
        assertDoesNotThrow(() -> ToolQuotaConfigValidator.validate(null, "task.toolQuotas"));
    }

    @Test
    @DisplayName("a well-formed config with a pool is valid")
    void wellFormed() {
        assertDoesNotThrow(() -> ToolQuotaConfigValidator.validate(config("updateAction", rule(100, "P1D", "improvements"), "updateSession",
                rule(100, "PT24H", "improvements"), "generateActionImage", rule(30, "P1D", null)), "task.toolQuotas"));
    }

    @Test
    @DisplayName("no tools, or a null rule, is rejected")
    void emptyTools() {
        assertTrue(rejection(new ToolQuotaConfig()).contains("tools is empty"));
        assertTrue(rejection(config("a", null)).contains("must not be null"));
    }

    @Test
    @DisplayName("limit must be a positive integer")
    void limit() {
        assertTrue(rejection(config("a", rule(null, "P1D", null))).contains("limit must be a positive integer"));
        assertTrue(rejection(config("a", rule(0, "P1D", null))).contains("limit must be a positive integer"));
        assertTrue(rejection(config("a", rule(-3, "P1D", null))).contains("limit must be a positive integer"));
    }

    @Test
    @DisplayName("window must be an ISO-8601 duration between PT1M and P31D")
    void window() {
        assertTrue(rejection(config("a", rule(1, null, null))).contains("window is required"));
        assertTrue(rejection(config("a", rule(1, "daily", null))).contains("not an ISO-8601 duration"));
        assertTrue(rejection(config("a", rule(1, "PT59S", null))).contains("shorter than the minimum PT1M"));
        assertTrue(rejection(config("a", rule(1, "P32D", null))).contains("longer than the maximum P31D"));
        assertDoesNotThrow(() -> ToolQuotaConfigValidator.validate(config("a", rule(1, "PT1M", null)), "p"));
        assertDoesNotThrow(() -> ToolQuotaConfigValidator.validate(config("a", rule(1, "P31D", null)), "p"));
    }

    @Test
    @DisplayName("tools of one pool must share limit and window")
    void poolMustAgree() {
        assertTrue(rejection(config("a", rule(100, "P1D", "p"), "b", rule(50, "P1D", "p"))).contains("must share limit and window"));
        assertTrue(rejection(config("a", rule(100, "P1D", "p"), "b", rule(100, "PT12H", "p"))).contains("must share limit and window"));
        // the same limit in different pools is fine
        assertDoesNotThrow(() -> ToolQuotaConfigValidator.validate(config("a", rule(100, "P1D", "p"), "b", rule(50, "P1D", "q")), "p"));
    }

    @Test
    @DisplayName("pool names are bounded and plain")
    void poolName() {
        assertTrue(rejection(config("a", rule(1, "P1D", "has space"))).contains("pool must be"));
        assertTrue(rejection(config("a", rule(1, "P1D", ""))).contains("pool must be"));
        assertTrue(rejection(config("a", rule(1, "P1D", "x".repeat(65)))).contains("pool must be"));
    }
}
