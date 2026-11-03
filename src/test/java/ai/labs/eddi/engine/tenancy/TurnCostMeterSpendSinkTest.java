/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.pricing.ISpendSink;
import ai.labs.eddi.engine.pricing.SpendEvent;
import ai.labs.eddi.engine.pricing.SpendSinks;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The attribution SPI fed by {@link TurnCostMeter}: deltas, exactly once,
 * isolated.
 */
class TurnCostMeterSpendSinkTest {

    private static final String CONVERSATION_ID = "0123456789abcdef01234567";

    private final List<SpendEvent> events = new ArrayList<>();
    private TenantQuotaService quotaService;
    private TurnCostMeter meter;

    @BeforeEach
    void setUp() {
        quotaService = mock(TenantQuotaService.class);
        when(quotaService.getDefaultTenantId()).thenReturn("default");
        when(quotaService.recordCost(anyString(), anyDouble())).thenReturn(QuotaCheckResult.OK);
        meter = new TurnCostMeter(quotaService, new SimpleMeterRegistry());
        ISpendSink failing = event -> {
            throw new IllegalStateException("sink down");
        };
        meter.spendSinks = new SpendSinks(List.of(failing, events::add));
    }

    private static IConversationMemory memoryWithCost(double cost) {
        IConversationMemory memory = new ConversationMemory(CONVERSATION_ID, "agent", 1, "user");
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, cost));
        return memory;
    }

    @Test
    void theSinkReceivesTheDeltaExactlyOnceAcrossPauseAndResume() {
        IConversationMemory paused = memoryWithCost(0.30);
        meter.meterSettledStep(paused);
        IConversationMemory resumed = ConversationMemoryUtilities
                .convertConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(paused));
        resumed.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.45));
        meter.meterSettledStep(resumed);
        meter.meterSettledStep(resumed);

        assertEquals(2, events.size());
        assertEquals(0.30, events.get(0).usd(), 1e-12);
        assertEquals(0.15, events.get(1).usd(), 1e-12);
        assertEquals(SpendEvent.Kind.TURN, events.get(0).kind());
        assertEquals(CONVERSATION_ID, events.get(0).conversationId());
        assertEquals("agent", events.get(0).agentId());
        assertEquals("user", events.get(0).userId());
        assertNull(events.get(0).groupId());
    }

    @Test
    void tokensAndUnpricedCallsAreDeltasToo() {
        IConversationMemory paused = memoryWithCost(0.30);
        paused.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_TOKEN_USAGE, Map.of("inputTokens", 100, "outputTokens", 40)));
        paused.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_PRICING, Map.of("unpricedCalls", 1)));
        meter.meterSettledStep(paused);
        IConversationMemory resumed = ConversationMemoryUtilities
                .convertConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(paused));
        resumed.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.45));
        resumed.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_TOKEN_USAGE, Map.of("inputTokens", 150, "outputTokens", 70)));
        resumed.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_PRICING, Map.of("unpricedCalls", 3)));
        meter.meterSettledStep(resumed);

        assertEquals(2, events.size());
        assertEquals(100, events.get(0).inputTokens());
        assertEquals(1, events.get(0).unpricedCalls());
        assertEquals(50, events.get(1).inputTokens());
        assertEquals(30, events.get(1).outputTokens());
        assertEquals(2, events.get(1).unpricedCalls());
    }

    @Test
    void aGroupMemberTurnCarriesTheGroupFromItsContext() {
        IConversationMemory memory = memoryWithCost(0.10);
        memory.getCurrentStep().storeData(new Data<>("context:groupId", new Context(Context.ContextType.string, "team-a")));
        memory.getCurrentStep().storeData(new Data<>("context:groupConversationId", new Context(Context.ContextType.string, "gc-1")));
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_PRICING, Map.of("unpricedCalls", 2L)));

        meter.meterSettledStep(memory);

        assertEquals("team-a", events.getFirst().groupId());
        assertEquals("gc-1", events.getFirst().groupConversationId());
        assertEquals(2, events.getFirst().unpricedCalls());
    }

    @Test
    void aChargeTheStoreCouldNotRecordIsNotDelivered() {
        when(quotaService.recordCost(anyString(), anyDouble())).thenReturn(QuotaCheckResult.unavailable("down"));
        meter.meterSettledStep(memoryWithCost(0.10));
        assertEquals(0, events.size());
    }
}
