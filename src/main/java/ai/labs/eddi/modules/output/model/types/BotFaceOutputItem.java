package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

@Getter
@Setter
@JsonSchemaTitle("botFace")
public class BotFaceOutputItem extends OutputItem {
    private String uri;
    private String alt;
    private int delay;

    public BotFaceOutputItem() {
        initType();
    }

    public BotFaceOutputItem(String uri, String alt, int delay) {
        initType();
        this.uri = uri;
        this.alt = alt;
        this.delay = delay;
    }

    @Override
    protected void initType() {
        super.type = "botFace";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BotFaceOutputItem that = (BotFaceOutputItem) o;
        return delay == that.delay && Objects.equals(uri, that.uri) && Objects.equals(alt, that.alt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, alt, delay);
    }
}
