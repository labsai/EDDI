/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.rules.impl.Rule;
import ai.labs.eddi.utils.PathNavigator;
import org.jboss.logging.Logger;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

public class SizeMatcher implements IRuleCondition {
    public static final String ID = "sizematcher";
    private static final String valuePathQualifier = "valuePath";
    private final String minQualifier = "min";
    private final String maxQualifier = "max";
    private final String equalQualifier = "equal";

    private String valuePath;
    private int max = -1;
    private int min = -1;
    private int equal = -1;

    private final IMemoryItemConverter memoryItemConverter;

    private static final Logger LOGGER = Logger.getLogger(SizeMatcher.class);

    public SizeMatcher(IMemoryItemConverter memoryItemConverter) {
        this.memoryItemConverter = memoryItemConverter;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getConfigs() {
        Map<String, String> configs = new HashMap<>();

        configs.put(valuePathQualifier, valuePath);
        configs.put(minQualifier, String.valueOf(min));
        configs.put(maxQualifier, String.valueOf(max));
        configs.put(equalQualifier, String.valueOf(equal));

        return configs;
    }

    @Override
    public void setConfigs(Map<String, String> configs) {
        if (configs != null && !configs.isEmpty()) {
            if (configs.containsKey(valuePathQualifier)) {
                valuePath = configs.get(valuePathQualifier);
            }

            if (configs.containsKey(minQualifier)) {
                min = Integer.parseInt(configs.get(minQualifier));
            }

            if (configs.containsKey(maxQualifier)) {
                max = Integer.parseInt(configs.get(maxQualifier));
            }

            if (configs.containsKey(equalQualifier)) {
                equal = Integer.parseInt(configs.get(equalQualifier));
            }
        }
    }

    @Override
    public ExecutionState execute(final IConversationMemory memory, final List<Rule> trace) throws Rule.RuntimeException {
        if (min == -1 && max == -1 && equal == -1) {
            return ExecutionState.NOT_EXECUTED;
        }

        int size = 0;
        try {
            Object rawValue = PathNavigator.getValue(valuePath, memoryItemConverter.convert(memory));
            size = determineSize(rawValue);
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }

        boolean isMin = true;
        boolean isMax = true;
        boolean isEqual = true;

        if (min != -1) {
            isMin = size >= min;
        }

        if (max != -1) {
            isMax = size <= max;
        }

        if (equal != -1) {
            isEqual = size == equal;
        }

        return isMin && isMax && isEqual ? ExecutionState.SUCCESS : ExecutionState.FAIL;
    }

    /**
     * Determines the size of the value the {@code valuePath} points to.
     * Collections, maps and arrays report their element count — this is the primary
     * use case ({@code memory.current.httpCalls.results}), which used to blow up in
     * {@code Integer.parseInt} and silently degrade to size 0. Numbers keep their
     * long-standing meaning of "the value <em>is</em> the size", and any other
     * scalar is measured by the length of its textual representation (a numeric
     * string is still read as a number for backwards compatibility).
     */
    static int determineSize(Object rawValue) {
        if (rawValue == null) {
            return 0;
        }

        if (rawValue instanceof Collection<?> collection) {
            return collection.size();
        }

        if (rawValue instanceof Map<?, ?> map) {
            return map.size();
        }

        if (rawValue.getClass().isArray()) {
            return Array.getLength(rawValue);
        }

        if (rawValue instanceof Number number) {
            return number.intValue();
        }

        String value = rawValue.toString().trim();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return value.length();
        }
    }

    public IRuleCondition clone() {
        IRuleCondition clone = new SizeMatcher(memoryItemConverter);
        clone.setConfigs(getConfigs());
        return clone;
    }
}
