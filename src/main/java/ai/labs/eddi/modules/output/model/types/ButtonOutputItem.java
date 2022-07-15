package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.Objects;

@Getter
@Setter
@JsonSchemaTitle("button")
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
}
