/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.ConversationSummaryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * The copy {@code resolveEffectiveSummaryConfig} builds when the summary config
 * inherits the parent's provider or model must keep every other field the
 * designer set — otherwise the summarizer silently runs on the defaults.
 */
@DisplayName("LlmTask — effective summary config keeps the configured limits")
class LlmTaskEffectiveSummaryConfigTest {

    @Test
    void inheritedProviderKeepsEveryConfiguredField() {
        var configured = new ConversationSummaryConfig();
        configured.setEnabled(true);
        configured.setMaxSummaryTokens(321);
        configured.setExcludePropertiesFromSummary(false);
        configured.setRecentWindowSteps(7);
        configured.setMaxRecallTurns(9);
        configured.setMaxTurnsPerUpdate(3);
        configured.setMaxCharsPerUpdate(4_000);
        configured.setSummarizationPrompt("summarise");

        var effective = LlmTask.resolveEffectiveSummaryConfig(configured, "openai", "gpt-x");

        assertNotSame(configured, effective);
        assertEquals("openai", effective.getLlmProvider());
        assertEquals("gpt-x", effective.getLlmModel());
        assertEquals(configured.isEnabled(), effective.isEnabled());
        assertEquals(321, effective.getMaxSummaryTokens());
        assertEquals(false, effective.isExcludePropertiesFromSummary());
        assertEquals(7, effective.getRecentWindowSteps());
        assertEquals(9, effective.getMaxRecallTurns());
        assertEquals(3, effective.getMaxTurnsPerUpdate());
        assertEquals(4_000, effective.getMaxCharsPerUpdate());
        assertEquals("summarise", effective.getSummarizationPrompt());
    }
}
