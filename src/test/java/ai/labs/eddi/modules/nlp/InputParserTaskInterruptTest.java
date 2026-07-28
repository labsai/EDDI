/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.TestMemoryFactory;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.internal.LifecycleManager;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding B2 — {@link InputParserTask} must NOT swallow the thread's interrupt
 * flag.
 *
 * <p>
 * {@code InputParserTask.execute} catches {@link InterruptedException} from the
 * parser and returns normally. The engine's graceful-stop signal is the
 * interrupt flag ({@code LifecycleManager} re-checks it before every task), so
 * consuming the exception without re-asserting the flag lets the remaining
 * tasks of an abandoned turn run anyway.
 * </p>
 *
 * <p>
 * The parser is stubbed to throw with the flag CLEAR — what every real blocking
 * JDK call does before it throws. Remove the
 * {@code Thread.currentThread().interrupt()} from the catch block and both
 * tests fail.
 * </p>
 */
@DisplayName("InputParserTask — interrupt flag restoration (B2)")
class InputParserTaskInterruptTest {

    private InputParserTask task;

    @BeforeEach
    void setUp() {
        // Never inherit a stale flag from an earlier test on this JUnit thread.
        Thread.interrupted();
        task = new InputParserTask(mock(IExpressionProvider.class), new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new ObjectMapper());
    }

    @AfterEach
    void clearInterruptFlag() {
        // These tests deliberately leave the thread interrupted — clear it so the
        // flag cannot leak into unrelated tests sharing this thread.
        Thread.interrupted();
    }

    @Test
    @Timeout(10)
    @DisplayName("catching InterruptedException re-asserts the flag and skips result storage")
    void execute_interruptedParser_restoresFlag() throws Exception {
        var ctx = TestMemoryFactory.createWithInput("hello");
        var parser = mock(IInputParser.class);
        doThrow(new InterruptedException("Execution was interrupted!"))
                .when(parser).normalize(anyString(), any());

        task.execute(ctx.memory(), parser);

        assertTrue(Thread.currentThread().isInterrupted(),
                "InputParserTask must re-assert the interrupt flag it consumed, otherwise "
                        + "LifecycleManager's graceful-stop check never fires");
        // The early return still holds: no parse result is written for the aborted
        // turn.
        verify(ctx.currentStep(), never()).storeData(
                argThat(data -> "expressions:parsed".equals(data.getKey())));
    }

    @Test
    @Timeout(10)
    @DisplayName("an interrupted parser task aborts the pipeline instead of running the next task")
    void pipeline_interruptedParserTask_stopsBeforeNextTask() throws Exception {
        var componentCache = mock(IComponentCache.class);
        var workflowId = mock(IResourceStore.IResourceId.class);
        when(workflowId.getId()).thenReturn("wf1");
        when(workflowId.getVersion()).thenReturn(1);

        var ctx = TestMemoryFactory.createWithInput("hello");
        var parser = mock(IInputParser.class);
        doThrow(new InterruptedException("Execution was interrupted!"))
                .when(parser).normalize(anyString(), any());

        // The parser task sits at absolute index 0 of workflow "wf1" version 1, so
        // LifecycleManager resolves its component under the key "wf1:1:0".
        Map<String, Object> parserComponents = new HashMap<>();
        parserComponents.put("wf1:1:0", parser);
        when(componentCache.getComponentMap(InputParserTask.ID)).thenReturn(parserComponents);
        when(componentCache.getComponentMap("ai.labs.behavior")).thenReturn(new HashMap<>());

        var nextTask = mock(ILifecycleTask.class);
        when(nextTask.getId()).thenReturn(new TaskId("ai.labs.behavior"));
        when(nextTask.getType()).thenReturn("behavior_rules");

        var lifecycleManager = new LifecycleManager(componentCache, workflowId);
        lifecycleManager.addLifecycleTask(task);
        lifecycleManager.addLifecycleTask(nextTask);

        assertThrows(LifecycleException.LifecycleInterruptedException.class,
                () -> lifecycleManager.executeLifecycle(ctx.memory(), null),
                "the restored flag must abort the turn at the next task boundary");

        // The decisive assertion: with the flag swallowed the pipeline sails on and
        // the abandoned turn keeps executing side-effectful tasks.
        verify(nextTask, never()).execute(any(), any());
    }
}
