/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.modules.output.model.QuickReply;
import ai.labs.eddi.modules.output.model.types.AgentFaceOutputItem;
import ai.labs.eddi.modules.output.model.types.ApplicationLinkOutputItem;
import ai.labs.eddi.modules.output.model.types.ButtonOutputItem;
import ai.labs.eddi.modules.output.model.types.ImageOutputItem;
import ai.labs.eddi.modules.output.model.types.InputFieldOutputItem;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the adapter's Markdown rendering of a turn.
 * <p>
 * The behaviour that matters: everything the shared text extractor drops —
 * quick replies above all — must still reach a client that can only display a
 * string. Before this renderer a wizard agent's whole set of answer options
 * vanished, which reads to a user as a broken agent rather than an unsupported
 * one.
 */
class OpenAiOutputRendererTest {

    private static SimpleConversationMemorySnapshot snapshot(ConversationOutput output) {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationOutputs(List.of(output));
        return snapshot;
    }

    /** A turn carrying a text item plus whatever else the caller passes. */
    private static SimpleConversationMemorySnapshot turn(String text, Object... items) {
        var output = new ConversationOutput();
        var all = new ArrayList<>();
        if (text != null) {
            all.add(new TextOutputItem(text, 0));
        }
        all.addAll(List.of(items));
        output.put("output", all);
        return snapshot(output);
    }

    private static SimpleConversationMemorySnapshot turnWithQuickReplies(String text, QuickReply... quickReplies) {
        var output = new ConversationOutput();
        output.put("output", List.of(new TextOutputItem(text, 0)));
        output.put("quickReplies", List.of(quickReplies));
        return snapshot(output);
    }

    // ─── the headline case ───

    @Test
    void quickRepliesAreRenderedAfterTheText() {
        var result = OpenAiOutputRenderer.render(turnWithQuickReplies("Which provider?",
                new QuickReply("Anthropic", "provider_anthropic", false),
                new QuickReply("OpenAI", "provider_openai", false)));

        assertEquals("Which provider?\n\n_Suggested replies:_ `Anthropic` · `OpenAI`", result);
    }

    @Test
    void quickReplyValueIsShown_notItsExpression() {
        // expressions is what the input matcher consumes, but a user typing into a
        // chat box types the label. Showing the expression would print internal
        // identifiers like "provider_anthropic" at the user.
        var result = OpenAiOutputRenderer.render(turnWithQuickReplies("Pick one",
                new QuickReply("Yes, please", "confirm_yes", true)));

        assertTrue(result.contains("`Yes, please`"), result);
        assertTrue(!result.contains("confirm_yes"), "the internal expression must not leak: " + result);
    }

    @Test
    void duplicateQuickRepliesCollapse_preservingOrder() {
        var result = OpenAiOutputRenderer.render(turnWithQuickReplies("Pick",
                new QuickReply("B", "b", false),
                new QuickReply("A", "a", false),
                new QuickReply("B", "b2", false)));

        assertEquals("Pick\n\n_Suggested replies:_ `B` · `A`", result);
    }

    @Test
    void blankQuickReplyValuesAreSkipped() {
        var result = OpenAiOutputRenderer.render(turnWithQuickReplies("Pick",
                new QuickReply("  ", "blank", false),
                new QuickReply(null, "nothing", false),
                new QuickReply("Real", "real", false)));

        assertEquals("Pick\n\n_Suggested replies:_ `Real`", result);
    }

    @Test
    void aTurnOfQuickRepliesAloneStillRenders() {
        var output = new ConversationOutput();
        output.put("quickReplies", List.of(new QuickReply("Start", "start", false)));

        assertEquals("_Suggested replies:_ `Start`", OpenAiOutputRenderer.render(snapshot(output)));
    }

    // ─── the other dropped types ───

    @Test
    void imagesRenderAsMarkdown() {
        var image = new ImageOutputItem();
        image.setUri("https://example.test/a.png");
        image.setAlt("A chart");

        assertEquals("Here\n\n![A chart](https://example.test/a.png)",
                OpenAiOutputRenderer.render(turn("Here", image)));
    }

    @Test
    void imageWithoutAltStillGetsAnAltText() {
        // "![](uri)" renders as a bare image with no accessible name; some clients
        // then show nothing at all where an image was intended.
        var image = new ImageOutputItem();
        image.setUri("https://example.test/a.png");

        assertEquals("![image](https://example.test/a.png)",
                OpenAiOutputRenderer.render(turn(null, image)));
    }

    @Test
    void applicationLinksRenderAsMarkdownLinks() {
        var link = new ApplicationLinkOutputItem();
        link.setPath("/orders/42");
        link.setLabel("Your order");

        assertEquals("[Your order](/orders/42)", OpenAiOutputRenderer.render(turn(null, link)));
    }

    @Test
    void applicationLinkWithoutLabelFallsBackToThePath() {
        var link = new ApplicationLinkOutputItem();
        link.setPath("/orders/42");

        assertEquals("[/orders/42](/orders/42)", OpenAiOutputRenderer.render(turn(null, link)));
    }

    @Test
    void buttonsRenderAsTheirLabelOnly() {
        // onPress is a client-side instruction with no meaning over this protocol,
        // so it must not be serialized at the user.
        var button = new ButtonOutputItem();
        button.setLabel("Confirm");
        button.setOnPress(Map.of("action", "submitForm", "url", "/internal/submit"));

        String result = OpenAiOutputRenderer.render(turn(null, button));

        assertEquals("**[Confirm]**", result);
        assertTrue(!result.contains("submitForm"), "onPress must not leak: " + result);
    }

    @Test
    void inputFieldsAreDescribed() {
        var field = new InputFieldOutputItem();
        field.setLabel("API Key");
        field.setSubType("password");

        String result = OpenAiOutputRenderer.render(turn("Paste it below", field));

        assertTrue(result.startsWith("Paste it below\n\n**API Key:**"), result);
        assertTrue(result.contains("cannot mask input"),
                "a password field over a plain chat channel is a warning, not a silent downgrade: " + result);
    }

    @Test
    void inputFieldFallsBackToItsPlaceholder() {
        var field = new InputFieldOutputItem();
        field.setPlaceholder("your@email.test");
        field.setSubType("email");

        assertEquals("**your@email.test:**", OpenAiOutputRenderer.render(turn(null, field)));
    }

    @Test
    void agentFaceIsDropped() {
        // An avatar has no text equivalent; captioning it would add a line the
        // agent author never wrote.
        var face = new AgentFaceOutputItem();
        face.setUri("https://example.test/face.png");
        face.setAlt("Avatar");

        assertEquals("Hello", OpenAiOutputRenderer.render(turn("Hello", face)));
    }

    // ─── shapes and edges ───

    @Test
    void textIsUnchangedWhenTheTurnHasNothingElse() {
        assertEquals("Just text", OpenAiOutputRenderer.render(turn("Just text")));
    }

    @Test
    void mapFormItemsRenderToo() {
        // A conversation rehydrated from the datastore yields Maps, not POJOs.
        var image = new LinkedHashMap<String, Object>();
        image.put("type", "image");
        image.put("uri", "https://example.test/a.png");
        image.put("alt", "Chart");

        var quickReply = new LinkedHashMap<String, Object>();
        quickReply.put("value", "Go");

        var output = new ConversationOutput();
        output.put("output", List.of(Map.of("type", "text", "text", "Look"), image));
        output.put("quickReplies", List.of(quickReply));

        assertEquals("Look\n\n![Chart](https://example.test/a.png)\n\n_Suggested replies:_ `Go`",
                OpenAiOutputRenderer.render(snapshot(output)));
    }

    @Test
    void unknownItemTypesAreDropped_notDumped() {
        // The pre-renderer failure mode was raw Java map dumps reaching the UI.
        var output = new ConversationOutput();
        output.put("output", List.of(Map.of("type", "somethingNew", "payload", "internal")));

        assertNull(OpenAiOutputRenderer.render(snapshot(output)));
    }

    @Test
    void itemsMissingTheirRequiredFieldAreDropped() {
        var image = new ImageOutputItem();
        var link = new ApplicationLinkOutputItem();
        link.setLabel("No path");
        var button = new ButtonOutputItem();

        assertEquals("Text only", OpenAiOutputRenderer.render(turn("Text only", image, link, button)));
    }

    @Test
    void nullAndEmptySnapshotsRenderAsNull() {
        assertNull(OpenAiOutputRenderer.render(null));
        assertNull(OpenAiOutputRenderer.render(new SimpleConversationMemorySnapshot()));
        assertNull(OpenAiOutputRenderer.render(snapshot(new ConversationOutput())));
    }

    @Test
    void nullItemsInTheOutputListAreSurvivable() {
        var items = new ArrayList<>();
        items.add(null);
        items.add(new TextOutputItem("Still here", 0));
        var output = new ConversationOutput();
        output.put("output", items);

        assertEquals("Still here", OpenAiOutputRenderer.render(snapshot(output)));
    }

    // ─── renderExtras: the streaming split ───

    @Test
    void renderExtrasOmitsTheTextTheModelAlreadyStreamed() {
        var result = OpenAiOutputRenderer.renderExtras(turnWithQuickReplies("Which provider?",
                new QuickReply("Anthropic", "a", false)));

        assertEquals("_Suggested replies:_ `Anthropic`", result,
                "the streamed prose must not be repeated after the token deltas");
    }

    @Test
    void renderExtrasIsNullWhenTheTurnWasTextOnly() {
        assertNull(OpenAiOutputRenderer.renderExtras(turn("Just text")));
    }
}
