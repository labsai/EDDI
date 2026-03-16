package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;

import java.util.Map;
import java.util.Objects;

public class ButtonOutputItem extends OutputItem {
    private String buttonType;
    private String label;
    private Map<String, Object> onPress;

    public ButtonOutputItem() {
        initType();
    }

    public ButtonOutputItem(String buttonType, String label, Map<String, Object> onPress) {
        initType();
        this.buttonType = buttonType;
        this.label = label;
        this.onPress = onPress;
    }

    @Override
    protected void initType() {
        super.type = "button";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ButtonOutputItem that = (ButtonOutputItem) o;
        return Objects.equals(buttonType, that.buttonType) && Objects.equals(label, that.label) &&
                Objects.equals(onPress, that.onPress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(buttonType, label, onPress);
    }

    public String getButtonType() {
        return buttonType;
    }

    public void setButtonType(String buttonType) {
        this.buttonType = buttonType;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Map<String, Object> getOnPress() {
        return onPress;
    }

    public void setOnPress(Map<String, Object> onPress) {
        this.onPress = onPress;
    }
}
