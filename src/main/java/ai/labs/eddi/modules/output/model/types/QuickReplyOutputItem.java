package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

@Getter
@Setter
@JsonSchemaTitle("quickReply")
public class QuickReplyOutputItem extends OutputItem {
    private String value;
    private String expressions;
    private Boolean isDefault;

    public QuickReplyOutputItem() {
        initType();
    }

    public QuickReplyOutputItem(String value, String expressions, Boolean isDefault) {
        initType();
        this.value = value;
        this.expressions = expressions;
        this.isDefault = isDefault;
    }

    @Override
    protected void initType() {
        super.type = "quickReply";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        QuickReplyOutputItem that = (QuickReplyOutputItem) o;
        return Objects.equals(value, that.value) &&
                Objects.equals(expressions, that.expressions) &&
                Objects.equals(isDefault, that.isDefault);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, expressions, isDefault);
    }
}
