/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.SpendReservations;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.ClientContextGuard;
import ai.labs.eddi.modules.llm.tools.ConverseWithAgentTool;
import ai.labs.eddi.modules.llm.tools.DelegationBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A delegation's spend used to escape every ceiling of the delegating
 * conversation: the callee runs in a conversation of its own, and nothing
 * rolled its cost back or passed the caller's ceiling down. A model on an agent
 * with {@code maxCostPerConversationUsd} could keep delegating, and only the
 * tenant's monthly budget stopped it.
 */
@DisplayName("Delegation — the caller's cost ceiling")
class DelegationCostCeilingTest {

    private static final String CONVERSATION = "0123456789abcdef01234567";

    private IConversationService conversationService;
    private ConversationMemory memory;
    private SimpleConversationMemorySnapshot calleeTurn;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        memory = new ConversationMemory(CONVERSATION, "agent-a", 1, "user-1");
        calleeTurn = new SimpleConversationMemorySnapshot();
        when(conversationService.startConversation(any(), anyString(), any(), anyMap())).thenReturn(new ConversationResult("conv-b", null));
        doAnswer(invocation -> {
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(calleeTurn);
            return null;
        }).when(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    private ConverseWithAgentTool tool() {
        var config = new DynamicAgentConfig();
        config.setEnabled(true);
        config.setAllowDelegation(true);
        return new ConverseWithAgentTool(conversationService, "user-1", config, 0, null, null, new MemoryDelegationBudget(memory));
    }

    private void spent(double usd) {
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, usd));
    }

    @Test
    @DisplayName("the callee's spend is charged back and counts against the caller's ceiling")
    void calleeSpendIsChargedBack() {
        memory.setConversationCostCeilingUsd(1.0);
        spent(0.2);
        calleeTurn.setConversationCost(0.5);

        tool().converseWithAgent("agent-b", "hello", null);

        assertEquals(0.5, ((Number) memory.getCurrentStep().getData(MemoryKeys.AUDIT_DELEGATED_COST).getResult()).doubleValue(), 1e-9);
        assertEquals(0.7, LlmSpendGate.conversationCost(memory), 1e-9, "the conversation total includes the delegation");
        assertEquals(0.3, new MemoryDelegationBudget(memory).remainingUsd().getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("a continued conversation is charged only what this turn added")
    void continuedConversationIsChargedTheDelta() throws Exception {
        var owned = new ConversationMemorySnapshot();
        owned.setUserId("user-1");
        when(conversationService.getConversationMemorySnapshot("conv-b")).thenReturn(owned);
        // No cost recorded before (an empty snapshot totals 0); the turn reports 0.4.
        calleeTurn.setConversationCost(0.4);

        tool().converseWithAgent("agent-b", "again", "conv-b");

        assertEquals(0.4, LlmSpendGate.conversationCost(memory), 1e-9);
    }

    @Test
    @DisplayName("the caller's remaining allowance is passed down as the callee's ceiling")
    void remainingAllowanceIsPassedDown() throws Exception {
        memory.setConversationCostCeilingUsd(1.0);
        spent(0.75);

        tool().converseWithAgent("agent-b", "hello", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Context>> start = ArgumentCaptor.forClass(Map.class);
        verify(conversationService).startConversation(any(), eq("agent-b"), eq("user-1"), start.capture());
        assertEquals("0.25", start.getValue().get(ClientContextGuard.KEY_DELEGATION_MAX_COST).getValue());
        var input = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), input.capture(), anyBoolean(), any());
        assertEquals("0.25", input.getValue().getContext().get(ClientContextGuard.KEY_DELEGATION_MAX_COST).getValue());
    }

    @Test
    @DisplayName("without a ceiling nothing is passed down, but the spend is still recorded")
    void noCeilingNoContext() throws Exception {
        calleeTurn.setConversationCost(0.3);

        tool().converseWithAgent("agent-b", "hello", null);

        var input = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), input.capture(), anyBoolean(), any());
        assertNull(input.getValue().getContext().get(ClientContextGuard.KEY_DELEGATION_MAX_COST));
        assertEquals(0.3, LlmSpendGate.conversationCost(memory), 1e-9);
    }

    @Test
    @DisplayName("a caller at its ceiling does not delegate at all")
    void noDelegationPastTheCeiling() throws Exception {
        memory.setConversationCostCeilingUsd(0.5);
        spent(0.5);

        String result = tool().converseWithAgent("agent-b", "hello", null);

        assertTrue(result.contains("cost ceiling"), result);
        verify(conversationService, never()).startConversation(any(), anyString(), any(), anyMap());
    }

    @Test
    @DisplayName("the callee's spend gate takes the passed-down allowance as its ceiling")
    void calleeIsHeldToTheAllowance() {
        var callee = new ConversationMemory("abcdef0123456789abcdef01", "agent-b", 1, "user-1");
        // Earlier turns of the callee's conversation spent 2.00; this turn carries
        // 0.25.
        callee.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 2.0));
        callee.startNextStep();
        callee.getCurrentStep().storeData(new Data<>("context:" + ClientContextGuard.KEY_DELEGATION_MAX_COST,
                new Context(Context.ContextType.string, "0.25")));

        OptionalDouble ceiling = LlmSpendGate.effectiveCeiling(callee);
        assertEquals(2.25, ceiling.getAsDouble(), 1e-9, "per turn: the spend before it plus the allowance");

        callee.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.3));
        var refusal = new LlmSpendGate(null, null).check(callee);
        assertNotNull(refusal, "the callee must stop once the caller's allowance is spent");
        assertEquals(LlmSpendGate.REASON_CONVERSATION_CEILING, refusal.reason());
    }

    @Test
    @DisplayName("the callee's own, tighter ceiling still wins")
    void tighterOwnCeilingWins() {
        var callee = new ConversationMemory("abcdef0123456789abcdef01", "agent-b", 1, "user-1");
        callee.setConversationCostCeilingUsd(0.1);
        callee.getCurrentStep().storeData(new Data<>("context:" + ClientContextGuard.KEY_DELEGATION_MAX_COST,
                new Context(Context.ContextType.string, "5.0")));

        assertEquals(0.1, LlmSpendGate.effectiveCeiling(callee).getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("non-finite or non-positive charges are ignored")
    void badChargesIgnored() {
        var budget = new MemoryDelegationBudget(memory);
        budget.charge(Double.NaN);
        budget.charge(-1.0);
        budget.charge(0.0);

        assertNull(memory.getCurrentStep().getData(MemoryKeys.AUDIT_DELEGATED_COST));
        assertFalse(budget.remainingUsd().isPresent(), "no ceiling configured");
    }

    @Test
    @DisplayName("spend delegated in this turn lowers what is left of the allowance — the baseline does not rise with it")
    void nestedDelegationLowersTheAllowance() {
        var callee = new ConversationMemory("abcdef0123456789abcdef01", "agent-b", 1, "user-1");
        callee.getCurrentStep().storeData(new Data<>("context:" + ClientContextGuard.KEY_DELEGATION_MAX_COST,
                new Context(Context.ContextType.string, "0.25")));
        new MemoryDelegationBudget(callee).charge(0.2);

        assertEquals(0.05, new MemoryDelegationBudget(callee).remainingUsd().getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("a skipped message on a continued conversation is not charged")
    void skippedContinuationIsNotCharged() throws Exception {
        var owned = new ConversationMemorySnapshot();
        owned.setUserId("user-1");
        when(conversationService.getConversationMemorySnapshot("conv-b")).thenReturn(owned);
        calleeTurn.setConversationCost(0.4);
        doAnswer(invocation -> {
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onSkipped(calleeTurn);
            return null;
        }).when(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        tool().converseWithAgent("agent-b", "again", "conv-b");

        assertNull(memory.getCurrentStep().getData(MemoryKeys.AUDIT_DELEGATED_COST));
    }

    @Test
    @DisplayName("the start turn and the message turn share one allowance")
    void startAndMessageShareTheAllowance() throws Exception {
        memory.setConversationCostCeilingUsd(1.0);
        spent(0.75);
        var started = new ConversationMemory("abcdef0123456789abcdef01", "agent-b", 1, "user-1");
        started.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.2));
        when(conversationService.getConversationMemorySnapshot("conv-b"))
                .thenReturn(ConversationMemoryUtilities.convertConversationMemory(started));

        tool().converseWithAgent("agent-b", "hello", null);

        var input = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), input.capture(), anyBoolean(), any());
        double messageAllowance = Double
                .parseDouble(String.valueOf(input.getValue().getContext().get(ClientContextGuard.KEY_DELEGATION_MAX_COST).getValue()));
        assertEquals(0.05, messageAllowance, 1e-9);
    }

    @Test
    @DisplayName("a timed-out delegation is charged what it recorded so far, and the rest of its allowance is held for the turn")
    void timeoutChargesAndReserves() throws Exception {
        memory.setConversationCostCeilingUsd(1.0);
        spent(0.5);
        var config = new DynamicAgentConfig();
        config.setEnabled(true);
        config.setAllowDelegation(true);
        config.setDelegationTimeoutSeconds(1);
        // The callee never answers; by the time the caller gives up it has spent 0.1.
        doAnswer(invocation -> null).when(conversationService)
                .say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
        var running = new ConversationMemory("abcdef0123456789abcdef01", "agent-b", 1, "user-1");
        running.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.1));
        when(conversationService.getConversationMemorySnapshot("conv-b"))
                .thenReturn(new ConversationMemorySnapshot(), ConversationMemoryUtilities.convertConversationMemory(running));
        var tool = new ConverseWithAgentTool(conversationService, "user-1", config, 0, null, null, new MemoryDelegationBudget(memory));

        String result = tool.converseWithAgent("agent-b", "hello", null);

        assertTrue(result.contains("Timeout"), result);
        assertEquals(0.1, ((Number) memory.getCurrentStep().getData(MemoryKeys.AUDIT_DELEGATED_COST).getResult()).doubleValue(), 1e-9);
        // 0.5 allowance: 0.1 charged, 0.4 held — nothing left to hand out again this
        // turn.
        assertEquals(0.0, new MemoryDelegationBudget(memory).remainingUsd().getAsDouble(), 1e-9);
    }

    private static ConversationMemorySnapshot spentSoFar(double usd) {
        var callee = new ConversationMemory("abcdef0123456789abcdef01", "agent-b", 1, "user-1");
        callee.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, usd));
        return ConversationMemoryUtilities.convertConversationMemory(callee);
    }

    @Test
    @DisplayName("timeout after a paid start: 0.50 handed down, 0.20 start, 0.05 message — 0.25 charged and 0.25 held, nothing reusable")
    void timeoutAfterPaidStartHoldsTheOriginalRemainder() throws Exception {
        memory.setConversationCostCeilingUsd(1.0);
        spent(0.5);
        var config = new DynamicAgentConfig();
        config.setEnabled(true);
        config.setAllowDelegation(true);
        config.setDelegationTimeoutSeconds(1);
        doAnswer(invocation -> null).when(conversationService)
                .say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
        // Read after the start (0.20 spent), then when the caller gives up (0.25
        // spent).
        when(conversationService.getConversationMemorySnapshot("conv-b")).thenReturn(spentSoFar(0.2), spentSoFar(0.25));
        var tool = new ConverseWithAgentTool(conversationService, "user-1", config, 0, null, null, new MemoryDelegationBudget(memory));

        tool.converseWithAgent("agent-b", "hello", null);

        assertEquals(0.25, ((Number) memory.getCurrentStep().getData(MemoryKeys.AUDIT_DELEGATED_COST).getResult()).doubleValue(), 1e-9);
        // Held: 0.50 - 0.25 = 0.25. With the reduced message allowance it held 0.05,
        // leaving 0.20 to hand out again.
        assertEquals(0.0, new MemoryDelegationBudget(memory).remainingUsd().getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("say() failing after a paid start still charges the start, and releases the hold")
    void failedSayChargesTheStart() throws Exception {
        memory.setConversationCostCeilingUsd(1.0);
        spent(0.5);
        doThrow(new IllegalStateException("say failed")).when(conversationService)
                .say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
        when(conversationService.getConversationMemorySnapshot("conv-b")).thenReturn(spentSoFar(0.2));

        String result = tool().converseWithAgent("agent-b", "hello", null);

        assertTrue(result.contains("Error"), result);
        assertEquals(0.2, ((Number) memory.getCurrentStep().getData(MemoryKeys.AUDIT_DELEGATED_COST).getResult()).doubleValue(), 1e-9);
        assertEquals(0.3, new MemoryDelegationBudget(memory).remainingUsd().getAsDouble(), 1e-9, "nothing still held");
    }

    @Test
    @DisplayName("the allowance is held at admission: a parallel delegation of the same turn is not handed it again")
    void admissionHoldsTheAllowance() {
        memory.setConversationCostCeilingUsd(1.0);
        spent(0.5);
        var budget = new MemoryDelegationBudget(memory);

        var first = budget.admit();
        var second = budget.admit();

        assertEquals(0.5, first.allowance().getAsDouble(), 1e-9);
        assertEquals(0.0, second.allowance().getAsDouble(), 1e-9, "the first delegation holds the whole remainder");
        first.release();
        assertEquals(0.5, budget.admit().allowance().getAsDouble(), 1e-9, "released once settled");
    }

    @Test
    @DisplayName("admission reserves the headroom left at that moment, in one step: a reservation landing first is not handed out again")
    void admissionReservesTheCurrentHeadroom() {
        memory.setConversationCostCeilingUsd(1.0);
        spent(0.5);
        // Another call of this turn reserved 0.3 between any earlier read and this
        // admission.
        memory.getSpendReservations().tryReserve(0.5, 1.0, 0.3);

        var hold = new MemoryDelegationBudget(memory).admit();

        assertEquals(0.2, hold.allowance().getAsDouble(), 1e-9);
        assertEquals(0.5, memory.getSpendReservations().heldUsd(), 1e-9, "never above the ceiling");
        assertTrue(memory.getSpendReservations().reserveHeadroom(0.5, 1.0).isEmpty(), "nothing left");
    }

    @Test
    @DisplayName("no headroom left: the delegation is refused with an allowance of 0 and nothing is held")
    void zeroHeadroomRefuses() {
        memory.setConversationCostCeilingUsd(1.0);
        spent(1.0);

        var hold = new MemoryDelegationBudget(memory).admit();

        assertEquals(0.0, hold.allowance().getAsDouble(), 1e-9, "refused, not unbounded");
        assertEquals(0.0, memory.getSpendReservations().heldUsd(), 1e-9);
    }

    @Test
    @DisplayName("no ceiling: no allowance and no hold")
    void noCeilingHoldsNothing() {
        var hold = new MemoryDelegationBudget(memory).admit();

        assertTrue(hold.allowance().isEmpty());
        assertEquals(0.0, memory.getSpendReservations().heldUsd(), 1e-9);
    }

    @Test
    @DisplayName("admission reads the spend inside the reservations lock: a charge-back arriving meanwhile waits until the hold is taken")
    void chargeWaitsForAdmission() throws Exception {
        memory.setConversationCostCeilingUsd(1.0);
        spent(0.5);
        SpendReservations reservations = memory.getSpendReservations();
        SpendReadProbe probe = new SpendReadProbe(memory, reservations);
        // Another delegation's charge-back, started from inside the spend read.
        probe.whileReadingSpend = () -> new MemoryDelegationBudget(memory).charge(0.4);

        var hold = new MemoryDelegationBudget(probe.memory).admit();
        probe.join();

        assertTrue(probe.readUnderLock.get(), "the spend is read under the reservations monitor");
        assertTrue(probe.blockedDuringRead.get(), "the charge waited for the admission");
        assertEquals(0.5, hold.allowance().getAsDouble(), 1e-9, "the headroom as it was at the read");
        assertEquals(0.9, LlmSpendGate.conversationCost(memory), 1e-9, "the charge landed after");
    }

    @Test
    @DisplayName("admission is atomic: a parallel admission arriving during the spend read waits and finds the headroom taken")
    void parallelAdmissionWaits() throws Exception {
        memory.setConversationCostCeilingUsd(1.0);
        spent(0.5);
        SpendReservations reservations = memory.getSpendReservations();
        SpendReadProbe probe = new SpendReadProbe(memory, reservations);
        var second = new AtomicReference<DelegationBudget.Hold>();
        probe.whileReadingSpend = () -> second.set(new MemoryDelegationBudget(memory).admit());

        var first = new MemoryDelegationBudget(probe.memory).admit();
        probe.join();

        assertTrue(probe.blockedDuringRead.get(), "the second admission waited");
        assertEquals(0.5, first.allowance().getAsDouble(), 1e-9);
        assertEquals(0.0, second.get().allowance().getAsDouble(), 1e-9, "nothing handed out twice");
        assertEquals(0.5, reservations.heldUsd(), 1e-9, "never above the ceiling");
    }

    /** A callee conversation: earlier turns spent 2.00, this turn carries 0.25. */
    private static ConversationMemory delegatedCallee() {
        var callee = new ConversationMemory("abcdef0123456789abcdef01", "agent-b", 1, "user-1");
        callee.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 2.0));
        callee.startNextStep();
        callee.getCurrentStep().storeData(new Data<>("context:" + ClientContextGuard.KEY_DELEGATION_MAX_COST,
                new Context(Context.ContextType.string, "0.25")));
        return callee;
    }

    @Test
    @DisplayName("the effective ceiling is computed inside the reservations lock: a charge-back arriving during its read waits")
    void ceilingIsReadUnderTheLock() throws Exception {
        var callee = delegatedCallee();
        SpendReservations reservations = callee.getSpendReservations();
        SpendReadProbe probe = new SpendReadProbe(callee, reservations, true);
        probe.whileReadingSpend = () -> new MemoryDelegationBudget(callee).charge(5.0);

        var hold = new MemoryDelegationBudget(probe.memory).admit();
        probe.join();

        assertTrue(probe.readUnderLock.get(), "the ceiling's baseline is read under the reservations monitor");
        assertTrue(probe.blockedDuringRead.get(), "the charge waited for the admission");
        assertEquals(0.25, hold.allowance().getAsDouble(), 1e-9, "ceiling 2.25 less 2.00 spent, as at the read");
        assertEquals(7.0, LlmSpendGate.conversationCost(callee), 1e-9, "the charge landed after");
    }

    @Test
    @DisplayName("a charge landing on the current step between the ceiling's cost reads cannot turn the ceiling negative (unbounded)")
    void chargeBetweenCostReadsKeepsTheCeiling() {
        var callee = spy(delegatedCallee());
        var charged = new AtomicBoolean();
        doAnswer(invocation -> {
            // The charge-back lands while the baseline is being read.
            if (charged.compareAndSet(false, true)) {
                callee.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_DELEGATED_COST, 5.0));
            }
            return invocation.callRealMethod();
        }).when(callee).getPreviousSteps();

        OptionalDouble ceiling = LlmSpendGate.effectiveCeiling(callee);

        assertTrue(LlmSpendGate.ceilingApplies(ceiling), "a ceiling, never 'no ceiling'");
        assertEquals(2.25, ceiling.getAsDouble(), 1e-9, "the spend before this turn plus the allowance");
        assertNotNull(new LlmSpendGate(null, null).check(callee), "and 7.00 spent is refused against it");
    }

    @Test
    @DisplayName("a computed ceiling that is not a usable number means nothing is left: delegation and model calls are refused")
    void unusableComputedCeilingRefuses() {
        var callee = new ConversationMemory("abcdef0123456789abcdef01", "agent-b", 1, "user-1");
        // Spend before this turn plus the allowance overflows to infinity.
        callee.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, Double.MAX_VALUE));
        callee.startNextStep();
        callee.getCurrentStep().storeData(new Data<>("context:" + ClientContextGuard.KEY_DELEGATION_MAX_COST,
                new Context(Context.ContextType.string, String.valueOf(Double.MAX_VALUE))));

        OptionalDouble ceiling = LlmSpendGate.effectiveCeiling(callee);

        assertEquals(0.0, ceiling.getAsDouble(), 0.0, "floored to 0, not dropped");
        assertTrue(LlmSpendGate.ceilingApplies(ceiling));
        assertEquals(0.0, new MemoryDelegationBudget(callee).admit().allowance().getAsDouble(), 0.0, "refused, not unbounded");
        assertNotNull(new LlmSpendGate(null, null).check(callee));
    }

    /**
     * A spy on the conversation memory that, the first time the spend is read while
     * the reservations monitor is held, starts {@link #whileReadingSpend} on
     * another thread and waits until that thread is blocked on the monitor.
     */
    private static final class SpendReadProbe {
        final ConversationMemory memory;
        final AtomicBoolean readUnderLock = new AtomicBoolean();
        final AtomicBoolean blockedDuringRead = new AtomicBoolean();
        Runnable whileReadingSpend;
        private Thread other;

        SpendReadProbe(ConversationMemory real, SpendReservations reservations) {
            this(real, reservations, false);
        }

        /**
         * @param ceilingRead
         *            hook the effective ceiling's baseline read
         *            ({@code getPreviousSteps}) instead of the spend read
         */
        SpendReadProbe(ConversationMemory real, SpendReservations reservations, boolean ceilingRead) {
            memory = spy(real);
            var hook = doAnswer(invocation -> {
                if (Thread.holdsLock(reservations) && other == null) {
                    readUnderLock.set(true);
                    other = new Thread(whileReadingSpend);
                    other.start();
                    blockedDuringRead.set(awaitBlocked(other));
                }
                return invocation.callRealMethod();
            });
            if (ceilingRead) {
                hook.when(memory).getPreviousSteps();
            } else {
                hook.when(memory).getAllSteps();
            }
        }

        void join() throws InterruptedException {
            if (other != null) {
                other.join(5_000);
                assertFalse(other.isAlive(), "the other thread finished");
            }
        }

        private static boolean awaitBlocked(Thread thread) throws InterruptedException {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                if (thread.getState() == Thread.State.BLOCKED) {
                    return true;
                }
                Thread.sleep(1);
            }
            return false;
        }
    }

    @Test
    @DisplayName("an interrupted wait is handled like a timeout: recorded spend charged, the rest held, the interrupt restored")
    void interruptedWaitKeepsTheHold() throws Exception {
        memory.setConversationCostCeilingUsd(1.0);
        spent(0.5);
        doAnswer(invocation -> {
            // The tool's own timeout cancels the worker while it waits.
            Thread.currentThread().interrupt();
            return null;
        }).when(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
        when(conversationService.getConversationMemorySnapshot("conv-b")).thenReturn(spentSoFar(0.0), spentSoFar(0.1));

        String result = tool().converseWithAgent("agent-b", "hello", null);

        assertTrue(Thread.interrupted(), "the interrupt flag is restored");
        assertTrue(result.contains("Stopped waiting"), result);
        assertEquals(0.1, ((Number) memory.getCurrentStep().getData(MemoryKeys.AUDIT_DELEGATED_COST).getResult()).doubleValue(), 1e-9);
        assertEquals(0.0, new MemoryDelegationBudget(memory).remainingUsd().getAsDouble(), 1e-9, "the rest stays held");
    }
}
