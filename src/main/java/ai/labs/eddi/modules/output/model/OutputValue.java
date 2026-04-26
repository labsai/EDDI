/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model;

import java.util.List;

/**
 * @author ginccc
 */
public class OutputValue {
    private List<OutputItem> valueAlternatives;

    public OutputValue() {
    }

    public OutputValue(List<OutputItem> valueAlternatives) {
        this.valueAlternatives = valueAlternatives;
    }

    public List<OutputItem> getValueAlternatives() {
        return valueAlternatives;
    }

    public void setValueAlternatives(List<OutputItem> valueAlternatives) {
        this.valueAlternatives = valueAlternatives;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        OutputValue that = (OutputValue) o;
        return java.util.Objects.equals(valueAlternatives, that.valueAlternatives);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(valueAlternatives);
    }
}
