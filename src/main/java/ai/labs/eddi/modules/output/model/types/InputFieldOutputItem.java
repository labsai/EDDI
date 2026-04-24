/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;

import java.util.Objects;

public class InputFieldOutputItem extends OutputItem {
    private String subType;
    private String placeholder;
    private String label;
    private String defaultValue;
    private Validation validation;

    public InputFieldOutputItem() {
        initType();
    }

    public InputFieldOutputItem(String subType, String placeholder, String label, String defaultValue, Validation validation) {
        initType();
        this.placeholder = placeholder;
        this.label = label;
        this.defaultValue = defaultValue;
        this.validation = validation;
        this.subType = subType;
    }

    @Override
    protected void initType() {
        super.type = "inputField";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        InputFieldOutputItem that = (InputFieldOutputItem) o;
        return Objects.equals(subType, that.subType) && Objects.equals(placeholder, that.placeholder) && Objects.equals(label, that.label)
                && Objects.equals(defaultValue, that.defaultValue) && Objects.equals(validation, that.validation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subType, placeholder, label, defaultValue, validation);
    }

    private record Validation(Integer minLength, Integer maxLength, String validationErrorMessage) {
    }

    public String getSubType() {
        return subType;
    }

    public void setSubType(String subType) {
        this.subType = subType;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public Validation getValidation() {
        return validation;
    }

    public void setValidation(Validation validation) {
        this.validation = validation;
    }
}
