package ai.labs.eddi.modules.output.model;

import ai.labs.eddi.modules.output.model.types.*;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Getter;
import lombok.Setter;

@JsonTypeInfo(
        visible = true,
        property = "type",
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextOutputItem.class, name = "text"),
        @JsonSubTypes.Type(value = ImageOutputItem.class, name = "image"),
        @JsonSubTypes.Type(value = BotFaceOutputItem.class, name = "botFace"),
        @JsonSubTypes.Type(value = QuickReplyOutputItem.class, name = "quickReply"),
        @JsonSubTypes.Type(value = InputFieldOutputItem.class, name = "inputField"),
        @JsonSubTypes.Type(value = ApplicationLinkOutputItem.class, name = "applicationLink"),
        @JsonSubTypes.Type(value = ButtonOutputItem.class, name = "button"),
        @JsonSubTypes.Type(value = OtherOutputItem.class, name = "other")
})
@Getter
@Setter
public abstract class OutputItem {
    protected String type;

    protected abstract void initType();
}

