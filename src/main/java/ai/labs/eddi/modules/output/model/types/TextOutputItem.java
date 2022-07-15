package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

@Getter
@Setter
@JsonSchemaTitle("text")
public class TextOutputItem extends OutputItem {
    private String text;
    private int delay;

    public TextOutputItem() {
        initType();
    }

    public TextOutputItem(String text) {
        initType();
        this.text = text;
    }

    public TextOutputItem(String text, int delay) {
        initType();
        this.text = text;
        this.delay = delay;
    }

    @Override
    protected void initType() {
        super.type = "text";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TextOutputItem that = (TextOutputItem) o;
        return Objects.equals(text, that.text) && delay == that.delay;
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, delay);
    }

    @Override
    public String toString() {
        return text;
    }
}
