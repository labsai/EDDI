package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

@Getter
@Setter
public class InputFieldOutputItem extends OutputItem {
    private String placeholder;
    private String label;
    private String defaultValue;
    private Validation validation;

    public InputFieldOutputItem() {
        initType();
    }

    public InputFieldOutputItem(String placeholder, String label, String defaultValue, Validation validation) {
        initType();
        this.placeholder = placeholder;
        this.label = label;
        this.defaultValue = defaultValue;
        this.validation = validation;
    }

    @Override
    protected void initType() {
        super.type = "inputField";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InputFieldOutputItem that = (InputFieldOutputItem) o;
        return Objects.equals(placeholder, that.placeholder) &&
                Objects.equals(label, that.label) && Objects.equals(defaultValue, that.defaultValue) &&
                Objects.equals(validation, that.validation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(placeholder, label, defaultValue, validation);
    }

    @Getter
    @Setter
    private static class Validation {
        private Integer minLength;
        private Integer maxLength;
        private String validationErrorMessage;
    }
}
