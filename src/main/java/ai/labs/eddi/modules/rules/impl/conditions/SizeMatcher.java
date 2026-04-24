/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.rules.impl.Rule;
import ai.labs.eddi.utils.PathNavigator;
import org.jboss.logging.Logger;

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
            if (rawValue != null) {
                size = Integer.parseInt(rawValue.toString());
            }
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

    public IRuleCondition clone() {
        IRuleCondition clone = new SizeMatcher(memoryItemConverter);
        clone.setConfigs(getConfigs());
        return clone;
    }
}
