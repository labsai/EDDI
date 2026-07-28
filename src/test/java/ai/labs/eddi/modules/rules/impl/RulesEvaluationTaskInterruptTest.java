/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.internal.LifecycleManager;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding B2 — {@link RulesEvaluationTask} must NOT swallow the thread's
 * interrupt flag.
 *
 * <p>
 * The engine's graceful-stop signal <em>is</em> the interrupt flag:
 * {@code LifecycleManager} re-checks
 * {@code Thread.currentThread().isInterrupted()} before every task and aborts
 * the turn with {@link LifecycleException.LifecycleInterruptedException}. A
 * task that catches {@link InterruptedException} and then returns normally
 * therefore has to restore the flag — otherwise the rest of an abandoned turn
 * keeps running and (per finding C3) can persist its stale snapshot over a
 * newer turn's state.
 * </p>
 *
 * <p>
 * Both tests model the flag as already cleared when the exception arrives,
 * which is exactly what every blocking JDK call ({@code Future.get},
 * {@code latch.await}, {@code Thread.sleep}) does before it throws. Remove the
 * {@code Thread.currentThread().interrupt()} from the catch block and both
 * tests fail.
 * </p>
 */
@DisplayName("RulesEvaluationTask — interrupt flag restoration (B2)")
class RulesEvaluationTaskInterruptTest {

    private RulesEvaluationTask task;

    @BeforeEach
    void setUp() {
        // Never inherit a stale flag from an earlier test on this JUnit thread.
        Thread.interrupted();
        task = new RulesEvaluationTask(mock(IResourceClientLibrary.class), mock(IJsonSerialization.class),
                mock(IRuleDeserialization.class), mock(IExpressionProvider.class));
    }

    @AfterEach
    void clearInterruptFlag() {
        // These tests deliberately leave the thread interrupted — clear it so the
        // flag cannot leak into unrelated tests sharing this thread.
        Thread.interrupted();
    }

    @Test
    @Timeout(10)
    @DisplayName("catching InterruptedException re-asserts the interrupt flag")
    void execute_interruptedEvaluator_restoresFlag() throws Exception {
        var memory = mock(IConversationMemory.class);
        var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);

        var evaluator = mock(RulesEvaluator.class);
        // Thrown with the flag CLEAR, like any real blocking call would.
        when(evaluator.evaluate(memory)).thenThrow(new InterruptedException("Execution was interrupted!"));

        task.execute(memory, evaluator);

        assertTrue(Thread.currentThread().isInterrupted(),
                "RulesEvaluationTask must re-assert the interrupt flag it consumed, otherwise "
                        + "LifecycleManager's graceful-stop check never fires");
    }

    @Test
    @Timeout(10)
    @DisplayName("an interrupted rules task aborts the pipeline instead of running the next task")
    void pipeline_interruptedRulesTask_stopsBeforeNextTask() throws Exception {
        var componentCache = mock(IComponentCache.class);
        var workflowId = mock(IResourceStore.IResourceId.class);
        when(workflowId.getId()).thenReturn("wf1");
        when(workflowId.getVersion()).thenReturn(1);

        var memory = mock(IConversationMemory.class);
        var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(memory.getConversationId()).thenReturn("conv-b2");
        when(memory.getAgentId()).thenReturn("agent-b2");

        var evaluator = mock(RulesEvaluator.class);
        when(evaluator.evaluate(any(IConversationMemory.class)))
                .thenThrow(new InterruptedException("Execution was interrupted!"));

        // The rules task sits at absolute index 0 of workflow "wf1" version 1, so
        // LifecycleManager resolves its component under the key "wf1:1:0".
        Map<String, Object> rulesComponents = new HashMap<>();
        rulesComponents.put("wf1:1:0", evaluator);
        when(componentCache.getComponentMap(RulesEvaluationTask.ID)).thenReturn(rulesComponents);
        when(componentCache.getComponentMap("ai.labs.output")).thenReturn(new HashMap<>());

        var nextTask = mock(ILifecycleTask.class);
        when(nextTask.getId()).thenReturn(new TaskId("ai.labs.output"));
        when(nextTask.getType()).thenReturn("output");

        var lifecycleManager = new LifecycleManager(componentCache, workflowId);
        lifecycleManager.addLifecycleTask(task);
        lifecycleManager.addLifecycleTask(nextTask);

        assertThrows(LifecycleException.LifecycleInterruptedException.class,
                () -> lifecycleManager.executeLifecycle(memory, null),
                "the restored flag must abort the turn at the next task boundary");

        // The decisive assertion: with the flag swallowed the pipeline sails on and
        // the abandoned turn keeps executing side-effectful tasks.
        verify(nextTask, never()).execute(any(), any());
    }
}
