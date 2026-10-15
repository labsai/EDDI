/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.memory.ConversationOutputExtractor;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one turn did, read from the conversation's stored memory after it ran —
 * the facts every expectation is judged against.
 *
 * @param actions
 *            the {@code actions} the turn's step recorded (the last write wins,
 *            as for every reader of a step)
 * @param properties
 *            every conversation property after the turn, as raw values
 * @param output
 *            the turn's output text, or {@code ""}
 * @param toolCalls
 *            the tool calls of the turn, in order
 * @param costUsd
 *            the step's {@code audit:cost}, 0 when nothing priced ran
 * @param conversationState
 *            the conversation's state after the turn
 * @param modelNames
 *            the step's {@code audit:model_name} values
 * @since 6.5.0
 */
public record TurnObservation(List<String> actions, Map<String, Object> properties, String output, List<ObservedToolCall> toolCalls,
        double costUsd, String conversationState, Set<String> modelNames) {

    /**
     * One tool call.
     *
     * @param tool
     *            the name it was called by
     * @param arguments
     *            its arguments as the JSON the model or the task sent, may be null
     */
    public record ObservedToolCall(String tool, String arguments) {
    }

    public TurnObservation withCost(double usd, Set<String> models) {
        return new TurnObservation(actions, properties, output, toolCalls, usd, conversationState, models);
    }

    public TurnObservation withToolCalls(List<ObservedToolCall> calls) {
        return new TurnObservation(actions, properties, output, calls, costUsd, conversationState, modelNames);
    }

    /**
     * Reads the observation of the step at {@code stepIndex} of a stored
     * conversation; used for the start turn, which is stored before
     * {@code startConversation} returns.
     */
    public static TurnObservation fromSnapshot(ConversationMemorySnapshot snapshot, int stepIndex) {
        var data = new ArrayList<Map.Entry<String, Object>>();
        var steps = snapshot.getConversationSteps();
        if (steps != null && stepIndex >= 0 && stepIndex < steps.size()) {
            for (var workflow : steps.get(stepIndex).getWorkflows()) {
                for (var result : workflow.getLifecycleTasks()) {
                    data.add(new AbstractMap.SimpleEntry<>(result.getKey(), result.getResult()));
                }
            }
        }
        List<ConversationOutput> outputs = snapshot.getConversationOutputs();
        ConversationOutput output = outputs != null && stepIndex >= 0 && stepIndex < outputs.size() ? outputs.get(stepIndex) : null;
        return of(data, output, snapshot.getConversationProperties(), snapshot.getConversationState());
    }

    /**
     * Reads the observation of a turn from the snapshot its {@code say} completed
     * with ({@code returnDetailed=true}, {@code returnCurrentStepOnly=true}). That
     * snapshot, not a re-read of the store, is the record of the turn: the
     * completion callback can run before the turn has been written back.
     */
    public static TurnObservation fromTurnSnapshot(SimpleConversationMemorySnapshot snapshot) {
        var data = new ArrayList<Map.Entry<String, Object>>();
        var steps = snapshot.getConversationSteps();
        if (steps != null && !steps.isEmpty()) {
            for (var entry : steps.getLast().getConversationStep()) {
                data.add(new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));
            }
        }
        var outputs = snapshot.getConversationOutputs();
        ConversationOutput output = outputs != null && !outputs.isEmpty() ? outputs.getLast() : null;
        return of(data, output, snapshot.getConversationProperties(), snapshot.getConversationState());
    }

    private static TurnObservation of(List<Map.Entry<String, Object>> stepData, ConversationOutput lastOutput,
                                      Map<String, Property> conversationProperties, ConversationState conversationState) {
        List<String> actions = List.of();
        double cost = 0.0;
        Set<String> models = new LinkedHashSet<>();
        List<ObservedToolCall> toolCalls = new ArrayList<>();
        for (var data : stepData) {
            String key = data.getKey();
            Object result = data.getValue();
            if (key == null || result == null) {
                continue;
            }
            if (MemoryKeys.ACTIONS.key().equals(key) && result instanceof List<?> list) {
                actions = list.stream().map(String::valueOf).toList();
            } else if (MemoryKeys.AUDIT_COST.equals(key) && result instanceof Number number) {
                cost = number.doubleValue();
            } else if (MemoryKeys.AUDIT_MODEL_NAME.equals(key)) {
                models.add(String.valueOf(result));
            } else if (MemoryKeys.AUDIT_TOOL_CALLS.equals(key)) {
                toolCalls = toolCallsOf(result);
            }
        }

        String output = "";
        if (lastOutput != null) {
            String text = ConversationOutputExtractor.extractText(lastOutput);
            output = text != null ? text : "";
        }

        var properties = new LinkedHashMap<String, Object>();
        if (conversationProperties != null) {
            conversationProperties.forEach((name, property) -> {
                Object value = valueOf(property);
                if (value != null) {
                    properties.put(name, value);
                }
            });
        }

        String state = conversationState != null ? conversationState.name() : null;
        return new TurnObservation(actions, properties, output, toolCalls, cost, state, models);
    }

    /**
     * The LLM tool trace of a step ({@code audit:tool_calls}, shaped
     * {@code {"calls": [ … ]}}): its {@code tool_call} entries.
     */
    static List<ObservedToolCall> toolCallsOf(Object auditToolCalls) {
        var calls = new ArrayList<ObservedToolCall>();
        if (auditToolCalls instanceof Map<?, ?> map && map.get("calls") instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> call && "tool_call".equals(call.get("type")) && call.get("tool") != null) {
                    Object args = call.get("arguments");
                    calls.add(new ObservedToolCall(String.valueOf(call.get("tool")), args != null ? String.valueOf(args) : null));
                }
            }
        }
        return calls;
    }

    /** The raw value of a property, as a template would see it. */
    static Object valueOf(Property property) {
        if (property == null) {
            return null;
        }
        if (property.getValueString() != null) {
            return property.getValueString();
        }
        if (property.getValueObject() != null) {
            return property.getValueObject();
        }
        if (property.getValueList() != null) {
            return property.getValueList();
        }
        if (property.getValueInt() != null) {
            return property.getValueInt();
        }
        if (property.getValueLong() != null) {
            return property.getValueLong();
        }
        if (property.getValueFloat() != null) {
            return property.getValueFloat();
        }
        if (property.getValueDouble() != null) {
            return property.getValueDouble();
        }
        return property.getValueBoolean();
    }
}
