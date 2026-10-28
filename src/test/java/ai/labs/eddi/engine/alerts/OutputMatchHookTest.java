/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertCondition;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertScope;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.alerts.model.MatchOp;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationProperties;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.security.ConversationReviewPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ai.labs.eddi.engine.alerts.AlertTestData.AGENT;
import static ai.labs.eddi.engine.alerts.AlertTestData.CONVERSATION;
import static ai.labs.eddi.engine.alerts.AlertTestData.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DisplayName("OUTPUT_MATCH — the clean-turn hook and the evaluation")
class OutputMatchHookTest {

    private AlertRuleCache cache;
    private AlertDispatcher dispatcher;
    private AlertEngine engine;
    private OutputMatchHook hook;

    @BeforeEach
    void setUp() {
        cache = mock(AlertRuleCache.class);
        dispatcher = mock(AlertDispatcher.class);
        engine = mock(AlertEngine.class);
        hook = new OutputMatchHook(cache, dispatcher, engine);
    }

    private static AlertRule outputRule(String path, MatchOp op, Object value, Boolean includeValue) {
        return new AlertRule("6aba70011a25f8be7920e0ab", "Escalations", true, AlertScope.agent(AGENT), AlertSource.OUTPUT_MATCH,
                AlertCondition.outputMatch(path, op, value, includeValue), List.of(), null, null, null, NOW, null, NOW);
    }

    @SuppressWarnings("unchecked")
    private static IConversationMemory memory(Map<String, Object> properties, String output, List<String> actions) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn(AGENT);
        when(memory.getAgentVersion()).thenReturn(2);
        when(memory.getConversationId()).thenReturn(CONVERSATION);
        when(memory.getUserId()).thenReturn("owner-1");
        IConversationProperties props = mock(IConversationProperties.class);
        when(props.toMap()).thenReturn(properties);
        when(memory.getConversationProperties()).thenReturn(props);
        IWritableConversationStep step = mock(IWritableConversationStep.class);
        ConversationOutput out = new ConversationOutput();
        out.put("output", List.of(Map.of("type", "text", "text", output == null ? "" : output)));
        when(step.getConversationOutput()).thenReturn(out);
        IData<List<String>> data = mock(IData.class);
        when(data.getResult()).thenReturn(actions);
        when(step.getLatestData(MemoryKeys.ACTIONS)).thenReturn(data);
        when(memory.getCurrentStep()).thenReturn(step);
        return memory;
    }

    @Test
    @DisplayName("an agent without OUTPUT_MATCH rules costs one cache lookup: no memory read, nothing queued")
    void noRulesNoWork() {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn(AGENT);
        when(cache.cachedOutputNeeds(AGENT)).thenReturn(Optional.of(OutputNeeds.NONE));

        hook.onCleanTurn(memory);

        verify(memory).getAgentId();
        verifyNoMoreInteractions(memory);
        verify(cache).cachedOutputNeeds(AGENT);
        verifyNoMoreInteractions(cache);
        verifyNoInteractions(dispatcher, engine);
    }

    @Test
    @DisplayName("only what the rules read is copied, as a detached snapshot, and matched on the worker")
    void copiesOnlyWhatIsNeeded() {
        Map<String, Object> live = new HashMap<>(Map.of("escalate", true, "secret", "do not copy", "order", new HashMap<>(Map.of("id", 7))));
        IConversationMemory memory = memory(live, "{\"sentiment\":\"angry\"}", List.of("handoff_requested"));
        when(cache.cachedOutputNeeds(AGENT)).thenReturn(Optional.of(new OutputNeeds(true, Set.of("escalate", "order"), false, true)));

        TurnSnapshot snapshot = OutputMatchHook.snapshot(memory, new OutputNeeds(true, Set.of("escalate", "order"), false, true));
        hook.onCleanTurn(memory);

        assertEquals(Set.of("escalate", "order"), snapshot.properties().keySet());
        assertNull(snapshot.outputText(), "no rule reads the output");
        assertEquals(List.of("handoff_requested"), snapshot.actions());
        assertEquals("owner-1", snapshot.userId(), "carried only to be recorded with a stored value");
        // Detached: a later change to the live values does not reach the snapshot.
        ((Map<String, Object>) live.get("order")).put("id", 8);
        assertEquals(7, ((Map<?, ?>) snapshot.properties().get("order")).get("id"));
        verify(dispatcher).submit(any());
    }

    @Test
    @DisplayName("on a cache miss the needs are loaded on the worker and this turn copies everything, so nothing is missed")
    void cacheMiss() {
        IConversationMemory memory = memory(Map.of("escalate", true), "{\"a\":1}", List.of("x"));
        when(cache.cachedOutputNeeds(AGENT)).thenReturn(Optional.empty());
        when(dispatcher.submit(any())).thenReturn(true);
        TurnSnapshot all = OutputMatchHook.snapshot(memory, OutputNeeds.ALL);
        hook.onCleanTurn(memory);
        verify(dispatcher, times(2)).submit(any());
        assertEquals("{\"a\":1}", all.outputText());
        assertEquals(Map.of("escalate", true), all.properties());
    }

    @Test
    @DisplayName("on a cache miss with alerts off or the queue full, nothing is read from the turn and nothing else is queued")
    void cacheMissLoaderRefused() {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn(AGENT);
        when(cache.cachedOutputNeeds(AGENT)).thenReturn(Optional.empty());
        when(dispatcher.submit(any())).thenReturn(false);

        hook.onCleanTurn(memory);

        verify(dispatcher, times(1)).submit(any());
        verify(memory).getAgentId();
        verifyNoMoreInteractions(memory);
        verifyNoInteractions(engine);
    }

    @Test
    @DisplayName("a hook failure never reaches the turn")
    void neverThrows() {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenThrow(new IllegalStateException("boom"));
        hook.onCleanTurn(memory);
    }

    @Test
    @DisplayName("needs are derived from the enabled OUTPUT_MATCH rules only")
    void needsOf() {
        OutputNeeds needs = OutputNeeds.of(List.of(outputRule("properties.escalate.level", MatchOp.EXISTS, null, null),
                outputRule("output.json.sentiment", MatchOp.EQ, "angry", null), AlertTestData.ratingRule(0.5, null, null)));
        assertTrue(needs.any());
        assertEquals(Set.of("escalate"), needs.properties());
        assertTrue(needs.output());
        assertEquals(OutputNeeds.NONE, OutputNeeds.of(List.of(AlertTestData.ratingRule(0.5, null, null))));
    }

    @Test
    @DisplayName("the value goes into the alert only when the rule asks AND conversation review is on; cut to 280 characters")
    void includeValueNeedsReview() {
        IAlertEventStore store = mock(IAlertEventStore.class);
        when(store.insert(any())).thenAnswer(inv -> Optional.of(((AlertEvent) inv.getArgument(0)).withId("6aba70011a25f8be7920e0e1")));
        when(store.updateDelivery(any(), anyLong())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
        when(store.incrementWindow(any(), any(), any())).thenReturn(1L);
        ConversationReviewPolicy review = mock(ConversationReviewPolicy.class);
        AlertEngine real = new AlertEngine(cache, store, mock(AlertDeliveryService.class), AlertSettings.defaults(),
                new AlertMetrics(new SimpleMeterRegistry()), review, Clock.fixed(NOW, ZoneOffset.UTC));
        String longValue = "z".repeat(400);
        TurnSnapshot snapshot = new TurnSnapshot(CONVERSATION, AGENT, 2, Map.of("note", longValue), null, null);
        ArgumentCaptor<AlertEvent> inserted = ArgumentCaptor.forClass(AlertEvent.class);

        when(cache.rulesFor(AGENT)).thenReturn(List.of(outputRule("properties.note", MatchOp.EXISTS, null, true)));
        when(review.reviewOf(AGENT, 2)).thenReturn(Optional.empty());
        real.evaluateOutput(snapshot);
        when(review.reviewOf(AGENT, 2)).thenReturn(Optional.of(new AgentConfiguration.ConversationReview()));
        real.evaluateOutput(snapshot);
        when(cache.rulesFor(AGENT)).thenReturn(List.of(outputRule("properties.note", MatchOp.EXISTS, null, false)));
        real.evaluateOutput(snapshot);

        verify(store, times(3)).insert(inserted.capture());
        AlertMatch noReview = inserted.getAllValues().get(0).match();
        AlertMatch withReview = inserted.getAllValues().get(1).match();
        AlertMatch notAsked = inserted.getAllValues().get(2).match();
        assertNull(noReview.value());
        assertEquals(List.of("properties.note exists"), noReview.matched());
        assertEquals(280, withReview.value().codePointCount(0, withReview.value().length()));
        assertNull(notAsked.value());
        assertTrue(!inserted.getAllValues().get(0).summary().contains("zzz"));
    }

    @Test
    @DisplayName("a non-matching turn fires nothing")
    void noMatch() {
        IAlertEventStore store = mock(IAlertEventStore.class);
        AlertEngine real = new AlertEngine(cache, store, mock(AlertDeliveryService.class), AlertSettings.defaults(),
                new AlertMetrics(new SimpleMeterRegistry()), null, Clock.fixed(NOW, ZoneOffset.UTC));
        when(cache.rulesFor(AGENT)).thenReturn(List.of(outputRule("properties.escalate", MatchOp.EQ, true, null)));
        real.evaluateOutput(new TurnSnapshot(CONVERSATION, AGENT, 2, Map.of("escalate", false), null, null));
        verify(store, never()).insert(any());
    }
}
