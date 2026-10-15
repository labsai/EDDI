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

    public TurnObservation withToolCalls(List<ObservedToolCall> calls) {
        return new TurnObservation(actions, properties, output, calls, costUsd, conversationState, modelNames);
    }

    /**
     * Reads the observation of the step at {@code stepIndex} — the latest step for
     * a turn that just ran, step 0 for the start turn.
     */
    public static TurnObservation fromSnapshot(ConversationMemorySnapshot snapshot, int stepIndex) {
        List<String> actions = List.of();
        double cost = 0.0;
        Set<String> models = new LinkedHashSet<>();
        List<ObservedToolCall> toolCalls = new ArrayList<>();

        var steps = snapshot.getConversationSteps();
        if (steps != null && stepIndex >= 0 && stepIndex < steps.size()) {
            for (var workflow : steps.get(stepIndex).getWorkflows()) {
                for (var data : workflow.getLifecycleTasks()) {
                    String key = data.getKey();
                    Object result = data.getResult();
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
            }
        }

        String output = "";
        List<ConversationOutput> outputs = snapshot.getConversationOutputs();
        if (outputs != null && stepIndex >= 0 && stepIndex < outputs.size()) {
            String text = ConversationOutputExtractor.extractText(outputs.get(stepIndex));
            output = text != null ? text : "";
        }

        var properties = new LinkedHashMap<String, Object>();
        if (snapshot.getConversationProperties() != null) {
            snapshot.getConversationProperties().forEach((name, property) -> {
                Object value = valueOf(property);
                if (value != null) {
                    properties.put(name, value);
                }
            });
        }

        String state = snapshot.getConversationState() != null ? snapshot.getConversationState().name() : null;
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
