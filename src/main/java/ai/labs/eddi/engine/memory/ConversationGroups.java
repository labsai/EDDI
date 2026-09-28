/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.engine.model.Context;

import java.util.List;

/**
 * The group(s) a conversation belongs to — what {@code group}-visibility
 * memories are scoped to, on both the write paths (the {@code rememberFact}
 * tool and the {@code longTerm} property boundary) and recall.
 * <p>
 * {@code groupId} arrives as a <b>context</b> value —
 * {@code MemberTurnExecutor} and {@code GroupLifecycleOps} both inject it that
 * way, and nothing writes it as a conversation <em>property</em>. It is read
 * from the current step first, then from any earlier step, since a resumed turn
 * re-enters without the original context map. The property read is kept as a
 * last resort so a config that genuinely does set a {@code groupId} property
 * still works.
 */
public final class ConversationGroups {

    private static final String CONTEXT_KEY = "context:groupId";

    private ConversationGroups() {
    }

    public static List<String> resolveGroupIds(IConversationMemory memory) {
        var currentStep = memory.getCurrentStep();
        if (currentStep != null) {
            String fromCurrent = contextValueAsString(currentStep.getLatestData(CONTEXT_KEY));
            if (fromCurrent != null) {
                return List.of(fromCurrent);
            }
        }

        var allSteps = memory.getAllSteps();
        if (allSteps != null) {
            List<IData<Object>> priorEntries = allSteps.getAllLatestData(CONTEXT_KEY);
            if (priorEntries != null) {
                for (IData<Object> entry : priorEntries) {
                    String value = contextValueAsString(entry);
                    if (value != null) {
                        return List.of(value);
                    }
                }
            }
        }

        var props = memory.getConversationProperties();
        if (props != null && props.get("groupId") instanceof Property p && p.getValueString() != null) {
            return List.of(p.getValueString());
        }
        return List.of();
    }

    /**
     * The visibility a {@code longTerm} property is persisted with: its own, else
     * the agent's {@code userMemoryConfig.defaultVisibility}, else {@code global}
     * (the legacy unscoped behaviour). A {@code group} property outside any group
     * is stored as {@code self} — a group entry with no group matches no reader,
     * and {@code self} is the only scope that keeps it reachable without widening
     * it. The turn boundary and undo/redo both persist through this, so an undone
     * or redone property lands exactly where the turn put it.
     */
    public static Visibility persistedVisibility(Property property, AgentConfiguration.UserMemoryConfig config, List<String> groupIds) {
        Visibility vis = property.getVisibility() != null ? property.getVisibility() : configuredDefault(config);
        return vis == Visibility.group && (groupIds == null || groupIds.isEmpty()) ? Visibility.self : vis;
    }

    private static Visibility configuredDefault(AgentConfiguration.UserMemoryConfig config) {
        if (config == null || config.getDefaultVisibility() == null) {
            return Visibility.global;
        }
        try {
            return Visibility.valueOf(config.getDefaultVisibility());
        } catch (IllegalArgumentException e) {
            return Visibility.global;
        }
    }

    /** Unwraps a {@code context:*} data entry, which holds a {@link Context}. */
    private static String contextValueAsString(IData<?> data) {
        if (data == null || data.getResult() == null) {
            return null;
        }
        Object result = data.getResult();
        Object value = result instanceof Context ctx ? ctx.getValue() : result;
        if (value == null) {
            return null;
        }
        String asString = String.valueOf(value);
        return asString.isBlank() ? null : asString;
    }
}
