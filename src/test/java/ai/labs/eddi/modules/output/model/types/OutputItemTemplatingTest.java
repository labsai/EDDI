/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E13 — every {@link OutputItem} subtype must template its user-visible
 * strings. Before {@code OutputItem#applyTemplating} existed, only text, image
 * and quickReply were handled and the remaining five types shipped raw
 * "{properties.x}" template syntax straight to the client.
 */
class OutputItemTemplatingTest {

    /** Stands in for the templating engine: "{x}" resolves to "RESOLVED". */
    private static final UnaryOperator<String> TEMPLATING = value -> value == null ? null : value.replace("{x}", "RESOLVED");

    @Test
    void textOutputItem_isTemplated() {
        var original = new TextOutputItem("hello {x}", 500);

        var templated = (TextOutputItem) original.applyTemplating(TEMPLATING);

        assertEquals("hello RESOLVED", templated.getText());
        assertEquals(500, templated.getDelay());
        assertEquals("text", templated.getType());
        assertEquals("hello {x}", original.getText(), "the original must stay untemplated");
    }

    @Test
    void imageOutputItem_isTemplated() {
        var original = new ImageOutputItem("https://example.com/{x}.png", "alt {x}");

        var templated = (ImageOutputItem) original.applyTemplating(TEMPLATING);

        assertEquals("https://example.com/RESOLVED.png", templated.getUri());
        assertEquals("alt RESOLVED", templated.getAlt());
        assertEquals("image", templated.getType());
    }

    @Test
    void quickReplyOutputItem_isTemplated() {
        var original = new QuickReplyOutputItem("value {x}", "expression({x})", true);

        var templated = (QuickReplyOutputItem) original.applyTemplating(TEMPLATING);

        assertEquals("value RESOLVED", templated.getValue());
        assertEquals("expression(RESOLVED)", templated.getExpressions());
        assertEquals(Boolean.TRUE, templated.getIsDefault());
        assertEquals("quickReply", templated.getType());
    }

    @Test
    void agentFaceOutputItem_isTemplated() {
        var original = new AgentFaceOutputItem("https://example.com/{x}.png", "face {x}", 250);

        var templated = (AgentFaceOutputItem) original.applyTemplating(TEMPLATING);

        assertEquals("https://example.com/RESOLVED.png", templated.getUri());
        assertEquals("face RESOLVED", templated.getAlt());
        assertEquals(250, templated.getDelay());
        assertEquals("agentFace", templated.getType());
    }

    @Test
    void inputFieldOutputItem_isTemplated() {
        var original = new InputFieldOutputItem();
        original.setSubType("password");
        original.setPlaceholder("Paste {x} here");
        original.setLabel("Label {x}");
        original.setDefaultValue("default {x}");

        var templated = (InputFieldOutputItem) original.applyTemplating(TEMPLATING);

        assertEquals("Paste RESOLVED here", templated.getPlaceholder());
        assertEquals("Label RESOLVED", templated.getLabel());
        assertEquals("default RESOLVED", templated.getDefaultValue());
        assertEquals("password", templated.getSubType());
        assertEquals("inputField", templated.getType());
        assertEquals("Paste {x} here", original.getPlaceholder(), "the original must stay untemplated");
    }

    @Test
    void applicationLinkOutputItem_isTemplated() {
        var original = new ApplicationLinkOutputItem("/app/{x}");
        original.setLabel("Open {x}");
        original.setDelay(100);

        var templated = (ApplicationLinkOutputItem) original.applyTemplating(TEMPLATING);

        assertEquals("/app/RESOLVED", templated.getPath());
        assertEquals("Open RESOLVED", templated.getLabel());
        assertEquals(100, templated.getDelay());
        assertEquals("applicationLink", templated.getType());
    }

    @Test
    void buttonOutputItem_isTemplated() {
        Map<String, Object> onPress = new LinkedHashMap<>();
        onPress.put("url", "https://example.com/{x}");
        onPress.put("timeout", 42);
        var original = new ButtonOutputItem("postback", "Press {x}", onPress);

        var templated = (ButtonOutputItem) original.applyTemplating(TEMPLATING);

        assertEquals("Press RESOLVED", templated.getLabel());
        assertEquals("postback", templated.getButtonType());
        assertEquals("https://example.com/RESOLVED", templated.getOnPress().get("url"));
        assertEquals(Integer.valueOf(42), templated.getOnPress().get("timeout"), "non-string payload values pass through untouched");
        assertEquals("button", templated.getType());
        assertEquals("https://example.com/{x}", onPress.get("url"), "the original payload must stay untemplated");
    }

    @Test
    void otherOutputItem_isTemplated() {
        var original = new OtherOutputItem();
        original.setType("other");
        original.put("someKey", "some {x} value");

        var templated = (OtherOutputItem) original.applyTemplating(TEMPLATING);

        assertEquals("some RESOLVED value", templated.get("someKey"));
        assertEquals("other", templated.getType());
        assertEquals("some {x} value", original.get("someKey"), "the original must stay untemplated");
    }

    @Test
    void nullFieldsAreTolerated() {
        var original = new TextOutputItem();

        var templated = (TextOutputItem) original.applyTemplating(TEMPLATING);

        assertNull(templated.getText());
        assertEquals("text", templated.getType());
    }

    /**
     * The registry guard: every output type reachable from the wire format has to
     * declare its own templating, so a new type cannot be added without deciding
     * which of its fields are templated.
     */
    @Test
    void everyRegisteredSubTypeDeclaresItsOwnTemplating() throws Exception {
        var subTypes = OutputItem.class.getAnnotation(JsonSubTypes.class);
        assertNotNull(subTypes);
        // One entry per output class. A retired type id is a second NAME on its
        // class's existing entry (botFace on AgentFaceOutputItem's, via
        // @JsonSubTypes.Type#names), never a second entry — so entries and classes
        // stay one-to-one and this count keeps meaning "every output class reachable
        // from the wire format".
        assertEquals(8, subTypes.value().length);

        for (var subType : subTypes.value()) {
            Class<?> type = subType.value();
            assertNotNull(type.getDeclaredMethod("templatedCopy", UnaryOperator.class),
                    type.getSimpleName() + " must declare templatedCopy(UnaryOperator)");

            var instance = (OutputItem) type.getDeclaredConstructor().newInstance();
            var templated = instance.applyTemplating(TEMPLATING);
            assertNotSame(instance, templated, type.getSimpleName() + " must template into a copy");
            assertSame(type, templated.getClass());
        }
    }
}
