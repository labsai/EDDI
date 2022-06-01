package ai.labs.eddi.modules.output.model;

import ai.labs.eddi.modules.output.model.types.ImageOutputItem;
import ai.labs.eddi.modules.output.model.types.OtherOutputItem;
import ai.labs.eddi.modules.output.model.types.QuickReplyOutputItem;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Getter;
import lombok.Setter;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextOutputItem.class, name = "text"),
        @JsonSubTypes.Type(value = ImageOutputItem.class, name = "image"),
        @JsonSubTypes.Type(value = QuickReplyOutputItem.class, name = "quickReply"),
        @JsonSubTypes.Type(value = OtherOutputItem.class, name = "other")
})
@Getter
@Setter
public abstract class OutputItem {
    protected String type;
}
