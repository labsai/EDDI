package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

@Getter
@Setter
@JsonSchemaTitle("applicationLink")
public class ApplicationLinkOutputItem extends OutputItem {
    private String path;

    public ApplicationLinkOutputItem() {
        initType();
    }

    public ApplicationLinkOutputItem(String path) {
        initType();
        this.path = path;
    }
    @Override
    protected void initType() {
        super.type = "applicationLink";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApplicationLinkOutputItem that = (ApplicationLinkOutputItem) o;
        return Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }

    @Override
    public String toString() {
        return path;
    }
}
