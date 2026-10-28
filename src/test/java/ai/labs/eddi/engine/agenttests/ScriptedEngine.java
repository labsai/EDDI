/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A stand-in for the conversation engine behind a mocked
 * {@link IConversationService}: a deterministic, rules-only "agent" whose every
 * turn is a scripted function of the input. The snapshots it hands out have the
 * real stored shape — steps of workflow results keyed {@code actions},
 * {@code audit:cost}, {@code audit:tool_calls}, conversation outputs and
 * properties — so the runner reads them exactly as it reads real memory.
 */
final class ScriptedEngine {

    /** What one turn of the scripted agent does. */
    record Step(List<String> actions, String output, Map<String, String> setProperties, double cost, ConversationState state,
            List<Map<String, Object>> toolCalls) {

        static Step of(List<String> actions, String output) {
            return new Step(actions, output, Map.of(), 0.0, ConversationState.READY, List.of());
        }

        Step withProperties(Map<String, String> properties) {
            return new Step(actions, output, properties, cost, state, toolCalls);
        }

        Step costing(double usd) {
            return new Step(actions, output, setProperties, usd, state, toolCalls);
        }

        Step ending(ConversationState newState) {
            return new Step(actions, output, setProperties, cost, newState, toolCalls);
        }

        Step calling(String tool, String argumentsJson) {
            var calls = new ArrayList<>(toolCalls);
            var call = new LinkedHashMap<String, Object>();
            call.put("type", "tool_call");
            call.put("tool", tool);
            call.put("arguments", argumentsJson);
            calls.add(call);
            return new Step(actions, output, setProperties, cost, state, calls);
        }
    }

    final IConversationService service = mock(IConversationService.class);
    final Map<String, ConversationMemorySnapshot> conversations = new ConcurrentHashMap<>();
    final Map<String, String> userIds = new ConcurrentHashMap<>();
    final List<String> environments = new ArrayList<>();
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger maxActive = new AtomicInteger();
    private final AtomicInteger ids = new AtomicInteger();
    /** Tools the "agent" calls on a given input, through the real mock registry. */
    final Map<String, List<String>> toolCallsByInput = new ConcurrentHashMap<>();
    volatile boolean neverAnswer;
    /** The handlers of turns that were never answered, to answer them late. */
    final List<ConversationResponseHandler> unanswered = new CopyOnWriteArrayList<>();
    volatile long startDelayMs;

    ScriptedEngine(Step onStart, Function<String, Step> script) throws Exception {
        when(service.startConversation(any(), anyString(), anyString(), any())).thenAnswer(inv -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                if (startDelayMs > 0) {
                    Thread.sleep(startDelayMs);
                }
                synchronized (environments) {
                    environments.add(((Environment) inv.getArgument(0)).name());
                }
                String id = String.format("%024x", ids.incrementAndGet());
                var snapshot = new ConversationMemorySnapshot();
                snapshot.setConversationId(id);
                snapshot.setUserId(inv.getArgument(2));
                snapshot.setConversationState(ConversationState.READY);
                snapshot.setConversationProperties(new LinkedHashMap<>());
                apply(snapshot, onStart);
                conversations.put(id, snapshot);
                userIds.put(id, inv.getArgument(2));
                return new IConversationService.ConversationResult(id, URI.create("eddi://conversation/" + id));
            } finally {
                active.decrementAndGet();
            }
        });

        doAnswer(inv -> {
            String conversationId = inv.getArgument(2);
            InputData input = inv.getArgument(6);
            ConversationResponseHandler handler = inv.getArgument(8);
            if (neverAnswer) {
                unanswered.add(handler);
                return null;
            }
            var snapshot = conversations.get(conversationId);
            var memory = mock(IConversationMemory.class);
            when(memory.getUserId()).thenReturn(snapshot.getUserId());
            for (String tool : toolCallsByInput.getOrDefault(input.getInput(), List.of())) {
                ToolMockRegistry.interceptToolCall(memory, tool, "{}", "http");
            }
            apply(snapshot, script.apply(input.getInput()));
            // What ConversationService hands the handler: the real conversion of the turn.
            handler.onComplete(ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(snapshot, inv.<Boolean>getArgument(3),
                    inv.<Boolean>getArgument(4), null));
            return null;
        }).when(service).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), isNull(), any(InputData.class), eq(false),
                any(ConversationResponseHandler.class));

        when(service.getConversationMemorySnapshot(anyString())).thenAnswer(inv -> conversations.get(inv.<String>getArgument(0)));
        when(service.getConversationState(anyString())).thenAnswer(inv -> conversations.get(inv.<String>getArgument(0)).getConversationState());
        doAnswer(inv -> {
            conversations.get(inv.<String>getArgument(0)).setConversationState(ConversationState.ENDED);
            return null;
        }).when(service).endConversation(anyString(), anyString());
    }

    private static void apply(ConversationMemorySnapshot snapshot, Step step) {
        var results = new ArrayList<ResultSnapshot>();
        results.add(new ResultSnapshot(MemoryKeys.ACTIONS.key(), step.actions(), null, new Date(), null, true));
        if (step.cost() > 0) {
            results.add(new ResultSnapshot(MemoryKeys.AUDIT_COST, step.cost(), null, new Date(), null, false));
            results.add(new ResultSnapshot(MemoryKeys.AUDIT_MODEL_NAME, "scripted-model", null, new Date(), null, false));
        }
        if (!step.toolCalls().isEmpty()) {
            results.add(new ResultSnapshot(MemoryKeys.AUDIT_TOOL_CALLS, Map.of("calls", step.toolCalls()), null, new Date(), null, false));
        }
        var workflow = new WorkflowRunSnapshot();
        workflow.setLifecycleTasks(results);
        var stepSnapshot = new ConversationStepSnapshot();
        stepSnapshot.setWorkflows(List.of(workflow));
        snapshot.getConversationSteps().add(stepSnapshot);

        var output = new ConversationOutput();
        output.put("actions", step.actions());
        if (step.output() != null) {
            output.put("output", List.of(Map.of("type", "text", "text", step.output())));
        }
        snapshot.getConversationOutputs().add(output);

        step.setProperties().forEach((name, value) -> snapshot.getConversationProperties().put(name,
                new Property(name, value, Property.Scope.conversation)));
        snapshot.setConversationState(step.state());
    }
}
