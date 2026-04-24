/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model;

import ai.labs.eddi.modules.output.model.types.*;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(visible = true, property = "type", use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes({@JsonSubTypes.Type(value = TextOutputItem.class, name = "text"), @JsonSubTypes.Type(value = ImageOutputItem.class, name = "image"),
        @JsonSubTypes.Type(value = AgentFaceOutputItem.class, name = "agentFace"),
        @JsonSubTypes.Type(value = QuickReplyOutputItem.class, name = "quickReply"),
        @JsonSubTypes.Type(value = InputFieldOutputItem.class, name = "inputField"),
        @JsonSubTypes.Type(value = ApplicationLinkOutputItem.class, name = "applicationLink"),
        @JsonSubTypes.Type(value = ButtonOutputItem.class, name = "button"), @JsonSubTypes.Type(value = OtherOutputItem.class, name = "other")})
public abstract class OutputItem {
    protected String type;

    protected abstract void initType();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
