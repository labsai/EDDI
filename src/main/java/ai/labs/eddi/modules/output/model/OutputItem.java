/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model;

import ai.labs.eddi.modules.output.model.types.*;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.function.UnaryOperator;

@JsonTypeInfo(visible = true, property = "type", use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes({@JsonSubTypes.Type(value = TextOutputItem.class, name = "text"), @JsonSubTypes.Type(value = ImageOutputItem.class, name = "image"),
        @JsonSubTypes.Type(value = AgentFaceOutputItem.class, name = "agentFace"),
        // Read-only alias for the retired v5 id: EDDI 5.x registered this very class
        // under "botFace". An unrecognized type id is fatal no matter what
        // FAIL_ON_UNKNOWN_PROPERTIES says, so without this entry a single legacy avatar
        // item makes the whole output document unreadable and the agent fails to
        // deploy. Writes stay on "agentFace" — AgentFaceOutputItem.initType() sets it
        // and its setType() canonicalizes the legacy id fed in by visible = true.
        @JsonSubTypes.Type(value = AgentFaceOutputItem.class, name = "botFace"),
        @JsonSubTypes.Type(value = QuickReplyOutputItem.class, name = "quickReply"),
        @JsonSubTypes.Type(value = InputFieldOutputItem.class, name = "inputField"),
        @JsonSubTypes.Type(value = ApplicationLinkOutputItem.class, name = "applicationLink"),
        @JsonSubTypes.Type(value = ButtonOutputItem.class, name = "button"), @JsonSubTypes.Type(value = OtherOutputItem.class, name = "other")})
public abstract class OutputItem {
    protected String type;

    protected abstract void initType();

    /**
     * Returns a templated copy of this output item: every user-visible string field
     * is passed through {@code templating} while the original item stays untouched
     * (the untemplated original is kept in conversation memory as the
     * {@code preTemplated} data entry).
     * <p>
     * The abstract {@link #templatedCopy(UnaryOperator)} hook is what makes this
     * safe to extend: a new {@link OutputItem} subtype cannot compile without
     * deciding which of its fields are templated, so no output type can silently
     * ship raw {@code {properties.x}} template syntax to the client.
     *
     * @param templating
     *            applied to each templatable field; must tolerate {@code null} and
     *            must never return {@code null} for a non-null input
     * @return a new instance — never {@code null}, never {@code this}
     */
    public final OutputItem applyTemplating(UnaryOperator<String> templating) {
        OutputItem templated = templatedCopy(templating);
        templated.setType(this.type);
        return templated;
    }

    /**
     * Creates the templated copy. Implementations only deal with their own fields;
     * {@link #applyTemplating(UnaryOperator)} carries over the type discriminator.
     */
    protected abstract OutputItem templatedCopy(UnaryOperator<String> templating);

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
