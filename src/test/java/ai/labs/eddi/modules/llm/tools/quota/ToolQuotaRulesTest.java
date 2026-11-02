/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import ai.labs.eddi.modules.llm.model.ToolQuotaConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static ai.labs.eddi.modules.llm.tools.quota.ToolQuotaConfigValidatorTest.config;
import static ai.labs.eddi.modules.llm.tools.quota.ToolQuotaConfigValidatorTest.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("toolQuotas rule resolution and windows")
class ToolQuotaRulesTest {

    @Test
    @DisplayName("the dispatch name wins over the slug, like toolRateLimits")
    void dispatchNameWins() {
        ToolQuotaConfig cfg = config("websearch", rule(30, "P1D", null), "searchNews", rule(5, "P1D", null));
        assertEquals(5, ToolQuotaRules.resolve(cfg, "searchNews", "websearch").limit());
        assertEquals("tool:searchNews", ToolQuotaRules.resolve(cfg, "searchNews", "websearch").counter());
        assertEquals(30, ToolQuotaRules.resolve(cfg, "searchWeb", "websearch").limit());
        assertEquals("tool:websearch", ToolQuotaRules.resolve(cfg, "searchWeb", "websearch").counter(),
                "a slug rule is one counter for all operations of the tool");
    }

    @Test
    @DisplayName("no rule, or no config, resolves to nothing")
    void noRule() {
        assertNull(ToolQuotaRules.resolve(null, "a", "a"));
        assertNull(ToolQuotaRules.resolve(config("other", rule(1, "P1D", null)), "a", "a"));
    }

    @Test
    @DisplayName("pooled rules resolve to the shared pool counter")
    void pool() {
        ToolQuotaConfig cfg = config("updateAction", rule(100, "P1D", "improvements"), "updateSession", rule(100, "P1D", " improvements "));
        assertEquals("pool:improvements", ToolQuotaRules.resolve(cfg, "updateAction", "updateAction").counter());
        assertEquals("pool:improvements", ToolQuotaRules.resolve(cfg, "updateSession", "updateSession").counter());
    }

    @Test
    @DisplayName("a stored rule that cannot be applied is reported, not run unbounded")
    void unusableStoredRule() {
        assertThrows(IllegalStateException.class, () -> ToolQuotaRules.resolve(config("a", rule(0, "P1D", null)), "a", "a"));
        assertThrows(IllegalStateException.class, () -> ToolQuotaRules.resolve(config("a", rule(5, "nonsense", null)), "a", "a"));
    }

    @Test
    @DisplayName("P1D windows align to 00:00 UTC; the boundary second belongs to the new window")
    void dayWindowBoundary() {
        Duration day = Duration.ofDays(1);
        long before = ToolQuotaRules.windowStart(Instant.parse("2026-11-02T23:59:59Z"), day);
        long after = ToolQuotaRules.windowStart(Instant.parse("2026-11-03T00:00:00Z"), day);
        assertEquals(Instant.parse("2026-11-02T00:00:00Z").getEpochSecond(), before);
        assertEquals(Instant.parse("2026-11-03T00:00:00Z").getEpochSecond(), after);
        assertNotEquals(before, after);
        assertEquals(Instant.parse("2026-11-03T00:00:00Z"), ToolQuotaRules.windowEnd(before, day));
    }

    @Test
    @DisplayName("window bounds: PT1M..P31D")
    void bounds() {
        assertEquals(Duration.ofMinutes(1), ToolQuotaRules.parseWindow("PT1M"));
        assertEquals(Duration.ofDays(31), ToolQuotaRules.parseWindow("P31D"));
        assertThrows(IllegalArgumentException.class, () -> ToolQuotaRules.parseWindow("PT30S"));
        assertThrows(IllegalArgumentException.class, () -> ToolQuotaRules.parseWindow("P31DT1S"));
    }
}
