/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.MemoryItemConverter;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static ai.labs.eddi.modules.llm.tools.quota.ToolQuotaConfigValidatorTest.config;
import static ai.labs.eddi.modules.llm.tools.quota.ToolQuotaConfigValidatorTest.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A context-sourced {@code subject} rendered by the real Qute engine over the
 * real {@link MemoryItemConverter} output, not a mock.
 * <p>
 * The context shape: a per-turn context variable of type {@code string} named
 * {@code personId} (stored in the step as {@code context:personId}) is
 * reachable as {@code {context.personId}}.
 */
@DisplayName("toolQuotas subject — rendered through the real templating engine")
class ToolQuotaSubjectRenderingTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    @DisplayName("{context.personId} renders to the context value and is the counted subject")
    void contextValueIsTheSubject() {
        var store = new InMemoryToolQuotaStore();
        var service = new ToolQuotaService(store, new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build()),
                new MemoryItemConverter(), new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));
        var memory = new ConversationMemory("bbbbbbbbbbbbbbbbbbbbbbbb", "aaaaaaaaaaaaaaaaaaaaaaaa", 1, "composite-user-acme-program");
        memory.getCurrentStep().storeData(new Data<>("context:personId", new Context(Context.ContextType.string, "p-9")));
        var cfg = config("updateAction", rule(1, "P1D", null));
        cfg.setSubject("{context.personId}");

        var gate = service.gateFor(cfg, ToolInvocation.of("updateAction"), memory, r -> false);
        assertTrue(gate.reserve().isEmpty());

        var key = new ToolQuotaKey("aaaaaaaaaaaaaaaaaaaaaaaa", "tool:updateAction", "p-9",
                ToolQuotaRules.windowStart(NOW, Duration.ofDays(1)));
        assertEquals(1, store.peek(key), "counted under the context value, not the composite conversation user id");
    }
}
