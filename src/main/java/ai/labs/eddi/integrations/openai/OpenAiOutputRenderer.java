/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.engine.memory.ConversationOutputExtractor;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.modules.output.model.QuickReply;
import ai.labs.eddi.modules.output.model.types.AgentFaceOutputItem;
import ai.labs.eddi.modules.output.model.types.ApplicationLinkOutputItem;
import ai.labs.eddi.modules.output.model.types.ButtonOutputItem;
import ai.labs.eddi.modules.output.model.types.ImageOutputItem;
import ai.labs.eddi.modules.output.model.types.InputFieldOutputItem;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.utils.RuntimeUtilities;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Renders a conversation turn as Markdown for an OpenAI-protocol client.
 * <p>
 * The OpenAI protocol carries one thing per message: a string. EDDI turns carry
 * eight output types, and
 * {@link ConversationOutputExtractor#extractResponse(SimpleConversationMemorySnapshot)}
 * — shared with group conversations and the agent-to-agent tools — keeps only
 * the text. Through that lens a wizard-style agent whose whole turn is
 * <em>"Which provider?"</em> plus five quick replies arrives as a question with
 * no visible answers, and looks broken rather than unsupported.
 * <p>
 * So this renderer takes the shared extractor's text verbatim — the wording of
 * a reply must not depend on which channel it left through — and appends a
 * Markdown rendering of everything the extractor drops. It deliberately does
 * not change the shared extractor, whose other callers feed agent-to-agent
 * prompts where interaction affordances would be noise.
 * <p>
 * Quick replies render as literal values rather than a numbered list: the user
 * types the next message by hand here, and numbering would invite "2" as an
 * answer, which no input matcher recognises.
 *
 * @since 6.1.0
 */
final class OpenAiOutputRenderer {

    private static final String KEY_OUTPUT = "output";
    private static final String KEY_QUICK_REPLIES = "quickReplies";

    private OpenAiOutputRenderer() {
        // Utility class
    }

    /**
     * Render the snapshot's last turn as Markdown.
     *
     * @param snapshot
     *            the snapshot, or {@code null}
     * @return the rendered turn, or {@code null} when the turn produced nothing a
     *         user could read — the caller substitutes its own notice
     */
    static String render(SimpleConversationMemorySnapshot snapshot) {
        String text = ConversationOutputExtractor.extractResponse(snapshot);
        String extras = renderExtras(snapshot);

        if (text == null || text.isBlank()) {
            return extras;
        }
        return extras == null ? text : text + "\n\n" + extras;
    }

    /**
     * Render only what the shared text extractor drops — images, links, buttons,
     * input fields and quick replies.
     * <p>
     * Split out for the streaming path: when an agent's model streamed the prose
     * token by token, the affordances are all that is still missing at
     * {@code onComplete}, and re-rendering the text there would send the whole
     * reply twice.
     *
     * @return the rendered affordances, or {@code null} when the turn had none
     */
    static String renderExtras(SimpleConversationMemorySnapshot snapshot) {
        ConversationOutput lastOutput = lastOutput(snapshot);
        if (lastOutput == null) {
            return null;
        }

        var blocks = new ArrayList<String>();
        for (Object item : asList(lastOutput.get(KEY_OUTPUT))) {
            String rendered = renderItem(item);
            if (rendered != null) {
                blocks.add(rendered);
            }
        }
        String quickReplies = renderQuickReplies(asList(lastOutput.get(KEY_QUICK_REPLIES)));
        if (quickReplies != null) {
            blocks.add(quickReplies);
        }

        return blocks.isEmpty() ? null : String.join("\n\n", blocks);
    }

    /**
     * Render one non-text output item.
     * <p>
     * Items reach us as typed POJOs from a turn that just ran, and as Maps when the
     * conversation was rehydrated from the datastore, so both shapes are handled.
     * Text is skipped because the shared extractor already emitted it, and
     * {@code agentFace} because an avatar has no text equivalent — captioning it
     * would add a line the agent author never wrote.
     */
    private static String renderItem(Object item) {
        return switch (item) {
            case TextOutputItem ignored -> null;
            case AgentFaceOutputItem ignored -> null;
            case ImageOutputItem i -> image(i.getUri(), i.getAlt());
            case ApplicationLinkOutputItem l -> link(l.getPath(), l.getLabel());
            case ButtonOutputItem b -> button(b.getLabel());
            case InputFieldOutputItem f -> inputField(f.getLabel(), f.getPlaceholder(), f.getSubType());
            case Map<?, ?> map -> renderMap(map);
            case null, default -> null;
        };
    }

    /** The Map form of {@link #renderItem}, for datastore-rehydrated turns. */
    private static String renderMap(Map<?, ?> map) {
        String type = string(map, "type");
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "image" -> image(string(map, "uri"), string(map, "alt"));
            case "applicationLink" -> link(string(map, "path"), string(map, "label"));
            case "button" -> button(string(map, "label"));
            case "inputField" -> inputField(string(map, "label"), string(map, "placeholder"), string(map, "subType"));
            default -> null;
        };
    }

    private static String image(String uri, String alt) {
        if (isBlank(uri)) {
            return null;
        }
        return "![" + (isBlank(alt) ? "image" : alt.trim()) + "](" + uri.trim() + ")";
    }

    private static String link(String path, String label) {
        if (isBlank(path)) {
            return null;
        }
        return "[" + (isBlank(label) ? path.trim() : label.trim()) + "](" + path.trim() + ")";
    }

    /**
     * A button's {@code onPress} is a client-side instruction — a route change, a
     * form submit — with no meaning over this protocol. Only the label survives,
     * marked as an affordance the user cannot actually press here.
     */
    private static String button(String label) {
        return isBlank(label) ? null : "**[" + label.trim() + "]**";
    }

    /**
     * The UI would swap its input control for this turn. A chat client cannot, so
     * the field is described instead — a user told "Password:" knows what to type,
     * where a user shown nothing does not.
     */
    private static String inputField(String label, String placeholder, String subType) {
        String prompt = firstNonBlank(label, placeholder);
        if (prompt == null) {
            return null;
        }
        String hint = "password".equalsIgnoreCase(subType) ? " _(sent as text — this channel cannot mask input)_" : "";
        return "**" + prompt.trim() + ":**" + hint;
    }

    /**
     * Render quick replies as their literal values, de-duplicated and in order.
     * <p>
     * {@code expressions} is what the input matcher consumes, but {@code value} is
     * what a chat UI puts on the button and therefore what the user would type, so
     * that is what is shown.
     */
    private static String renderQuickReplies(List<?> quickReplies) {
        var values = new LinkedHashSet<String>();
        for (Object quickReply : quickReplies) {
            String value = switch (quickReply) {
                case QuickReply qr -> qr.getValue();
                case Map<?, ?> map -> string(map, "value");
                case null, default -> null;
            };
            if (!isBlank(value)) {
                values.add(value.trim());
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        return "_Suggested replies:_ " + String.join(" · ", values.stream().map(v -> "`" + v + "`").toList());
    }

    private static ConversationOutput lastOutput(SimpleConversationMemorySnapshot snapshot) {
        if (snapshot == null || RuntimeUtilities.isNullOrEmpty(snapshot.getConversationOutputs())) {
            return null;
        }
        List<ConversationOutput> outputs = snapshot.getConversationOutputs();
        return outputs.get(outputs.size() - 1);
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String string(Map<?, ?> map, String key) {
        return map.get(key) instanceof String s ? s : null;
    }

    private static String firstNonBlank(String first, String second) {
        if (!isBlank(first)) {
            return first;
        }
        return isBlank(second) ? null : second;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
