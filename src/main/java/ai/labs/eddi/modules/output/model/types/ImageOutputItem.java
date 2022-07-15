package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

@Getter
@Setter
@JsonSchemaTitle("image")
public class ImageOutputItem extends OutputItem {
    private String uri;
    private String alt;

    public ImageOutputItem() {
        initType();
    }

    public ImageOutputItem(String uri, String alt) {
        initType();
        this.uri = uri;
        this.alt = alt;
    }

    @Override
    protected void initType() {
        super.type = "image";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ImageOutputItem that = (ImageOutputItem) o;
        return Objects.equals(uri, that.uri) && Objects.equals(alt, that.alt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, alt);
    }
}
