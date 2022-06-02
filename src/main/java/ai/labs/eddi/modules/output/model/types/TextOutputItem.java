package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

@Getter
@Setter
public class TextOutputItem extends OutputItem {

    @JsonProperty(required = true)
    private String text;
    private Integer delay;

    public TextOutputItem() {
        initType();
    }

    public TextOutputItem(String text) {
        initType();
        this.text = text;
    }

    public TextOutputItem(String text, Integer delay) {
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
        return Objects.equals(text, that.text) && Objects.equals(delay, that.delay);
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
