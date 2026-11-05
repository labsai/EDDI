/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.modules.llm.model.ToolQuotaConfig;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static ai.labs.eddi.modules.llm.tools.quota.ToolQuotaConfigValidatorTest.config;
import static ai.labs.eddi.modules.llm.tools.quota.ToolQuotaConfigValidatorTest.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code window: "RUN"}: a cap that holds for one run (a scheduled fire, a
 * group discussion, else the conversation) and starts again with the next one.
 */
@DisplayName("toolQuotas window RUN")
class ToolQuotaRunWindowTest {

    private static final String AGENT = ToolQuotaServiceTest.AGENT;

    private InMemoryToolQuotaStore store;
    private ToolQuotaServiceTest.MutableClock clock;
    private ToolQuotaService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryToolQuotaStore();
        clock = new ToolQuotaServiceTest.MutableClock("2026-11-02T10:00:00Z");
        IMemoryItemConverter converter = mock(IMemoryItemConverter.class);
        when(converter.convert(any())).thenReturn(Map.of());
        service = new ToolQuotaService(store, mock(ITemplatingEngine.class), converter, new SimpleMeterRegistry(), clock);
    }

    /** A memory whose current step carries the given context entries. */
    @SuppressWarnings("unchecked")
    private static IConversationMemory turn(String conversationId, Map<String, String> context) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn(AGENT);
        when(memory.getUserId()).thenReturn("scheduler");
        when(memory.getConversationId()).thenReturn(conversationId);
        IWritableConversationStep step = mock(IWritableConversationStep.class);
        context.forEach((key, value) -> {
            IData<Object> data = mock(IData.class);
            when(data.getResult()).thenReturn(new Context(Context.ContextType.string, value));
            when(step.getData("context:" + key)).thenReturn(data);
        });
        when(memory.getCurrentStep()).thenReturn(step);
        return memory;
    }

    private Optional<String> call(ToolQuotaConfig cfg, IConversationMemory memory) {
        return service.gateFor(cfg, ToolInvocation.of("search"), memory, r -> false).reserve();
    }

    private final ToolQuotaConfig twoPerRun = config("search", rule(2, "RUN", null));

    @Test
    @DisplayName("the cap holds within a run and the next run starts at zero")
    void capPerFire() {
        var fireA = turn("c1", Map.of(ScheduleConfiguration.CONTEXT_FIRE_ID, "fire-A"));
        var fireB = turn("c1", Map.of(ScheduleConfiguration.CONTEXT_FIRE_ID, "fire-B"));

        assertTrue(call(twoPerRun, fireA).isEmpty());
        assertTrue(call(twoPerRun, fireA).isEmpty());
        assertTrue(call(twoPerRun, fireA).isPresent(), "third call of fire A is over the cap");
        assertTrue(call(twoPerRun, fireB).isEmpty(), "fire B is a new run, even on the same persistent conversation");
    }

    @Test
    @DisplayName("a fire id wins over the group conversation, which wins over the conversation")
    void runIdPrecedence() {
        assertEquals("fire:f1",
                ToolQuotaService.runIdFor(turn("c1", Map.of(ScheduleConfiguration.CONTEXT_FIRE_ID, "f1", "groupConversationId", "g1"))));
        assertEquals("group:g1", ToolQuotaService.runIdFor(turn("c1", Map.of("groupConversationId", "g1"))));
        assertEquals("conversation:c1", ToolQuotaService.runIdFor(turn("c1", Map.of())));
        assertEquals("conversation:c1", ToolQuotaService.runIdFor(turn("c1", Map.of("groupConversationId", "  "))), "a blank id is no id");
    }

    @Test
    @DisplayName("group members of one discussion share nothing across discussions, scouts across agents stay separate")
    void groupRuns() {
        var run1 = turn("m1", Map.of("groupConversationId", "gc-1"));
        var run2 = turn("m2", Map.of("groupConversationId", "gc-2"));

        assertTrue(call(twoPerRun, run1).isEmpty());
        assertTrue(call(twoPerRun, run1).isEmpty());
        assertTrue(call(twoPerRun, run1).isPresent());
        assertTrue(call(twoPerRun, run2).isEmpty(), "the next cadence run");
    }

    @Test
    @DisplayName("a standalone agent is capped per conversation")
    void standaloneConversation() {
        var c1 = turn("conv-1", Map.of());
        var c2 = turn("conv-2", Map.of());

        assertTrue(call(twoPerRun, c1).isEmpty());
        assertTrue(call(twoPerRun, c1).isEmpty());
        assertTrue(call(twoPerRun, c1).isPresent());
        assertTrue(call(twoPerRun, c2).isEmpty());
    }

    @Test
    @DisplayName("time does not reset a run: the cap holds across the clock moving on")
    void timeDoesNotReset() {
        var fire = turn("c1", Map.of(ScheduleConfiguration.CONTEXT_FIRE_ID, "f"));
        call(twoPerRun, fire);
        call(twoPerRun, fire);

        clock.set("2026-11-03T10:00:00Z");

        assertTrue(call(twoPerRun, fire).isPresent(), "a day later it is still the same run");
    }

    @Test
    @DisplayName("the refusal tells the model the per-run limit is final and names no reset time")
    void refusalWording() {
        var fire = turn("c1", Map.of(ScheduleConfiguration.CONTEXT_FIRE_ID, "f"));
        call(twoPerRun, fire);
        call(twoPerRun, fire);

        String refusal = call(twoPerRun, fire).orElseThrow();

        assertTrue(refusal.contains("NOT_EXECUTED"));
        assertTrue(refusal.contains("per run"));
        assertFalse(refusal.contains("resets at"));
    }

    @Test
    @DisplayName("a per-run counter row outlives the run but is not kept forever")
    void rowExpiry() {
        var rule = ToolQuotaRules.resolve(twoPerRun, "search", "search");
        var key = new ToolQuotaKey(AGENT, "x", "s", 0L);

        assertEquals(Instant.parse("2026-12-03T10:00:00Z"), service.expiresAt(key, rule));
        assertNull(service.resetAt(key, rule));
    }

    @Test
    @DisplayName("precheck refuses an exhausted run without counting")
    void precheck() {
        var fire = turn("c1", Map.of(ScheduleConfiguration.CONTEXT_FIRE_ID, "f"));
        call(twoPerRun, fire);
        call(twoPerRun, fire);

        assertTrue(service.precheck(twoPerRun, "search", "search", fire).isPresent());
        assertTrue(service.precheck(twoPerRun, "search", "search", turn("c1", Map.of(ScheduleConfiguration.CONTEXT_FIRE_ID, "other"))).isEmpty());
    }

    @Test
    @DisplayName("save-time validation accepts RUN in any case and rejects mixing it into a pool with a time window")
    void validation() {
        ToolQuotaConfigValidator.validate(config("a", rule(5, "RUN", null)), "p");
        ToolQuotaConfigValidator.validate(config("a", rule(5, "run", "p1"), "b", rule(5, "RUN", "p1")), "p");

        assertThrows(IllegalArgumentException.class,
                () -> ToolQuotaConfigValidator.validate(config("a", rule(5, "RUN", "p1"), "b", rule(5, "P1D", "p1")), "p"));
        assertThrows(IllegalArgumentException.class, () -> ToolQuotaConfigValidator.validate(config("a", rule(0, "RUN", null)), "p"));
        assertThrows(IllegalArgumentException.class, () -> ToolQuotaConfigValidator.validate(config("a", rule(5, "RUNS", null)), "p"));
    }

    @Test
    @DisplayName("the window parser still refuses RUN as a duration: it is a scope, not a span")
    void parseWindowUnchanged() {
        assertTrue(ToolQuotaRules.isRunWindow(" RUN "));
        assertFalse(ToolQuotaRules.isRunWindow("P1D"));
        assertThrows(IllegalArgumentException.class, () -> ToolQuotaRules.parseWindow("RUN"));
    }
}
