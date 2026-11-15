/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.reply;

import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Finding the step a mail arrived in, and the text the agent replied with, in a
 * conversation's outputs.
 */
class EmailReplyThreadTest {

    private static Map<String, Object> output(Object items) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("output", items);
        return output;
    }

    private static Map<String, Object> mailOutput(String messageId, Object items) {
        Map<String, Object> output = output(items);
        output.put("context", Map.of("mail", Map.of("messageId", messageId)));
        return output;
    }

    @Test
    void theLastStepCarryingTheMessageIdIsFound() {
        List<Map<String, Object>> outputs = List.of(output(List.of("welcome")), mailOutput("<a@x>", List.of("first")),
                output(List.of("aside")), mailOutput("<b@x>", List.of("second")), mailOutput("<a@x>", List.of("again")));
        assertEquals(4, EmailReplyThread.mailStep(outputs, "<a@x>"));
        assertEquals(3, EmailReplyThread.mailStep(outputs, "<b@x>"));
        assertEquals(-1, EmailReplyThread.mailStep(outputs, "<c@x>"));
        assertEquals(-1, EmailReplyThread.mailStep(null, "<a@x>"));
        assertEquals(-1, EmailReplyThread.mailStep(outputs, null));
    }

    @Test
    void mailOfReadsOnlyAMapUnderTheMailKey() {
        assertNull(EmailReplyThread.mailOf(null));
        assertNull(EmailReplyThread.mailOf(output(List.of())));
        Map<String, Object> notAMap = output(List.of());
        notAMap.put("context", "text");
        assertNull(EmailReplyThread.mailOf(notAMap));
        Map<String, Object> mailNotAMap = output(List.of());
        mailNotAMap.put("context", Map.of("mail", "raw"));
        assertNull(EmailReplyThread.mailOf(mailNotAMap));
        assertEquals("<a@x>", EmailReplyThread.mailOf(mailOutput("<a@x>", List.of())).get("messageId"));
    }

    @Test
    void theFirstMailsReplyIncludesTheStartTurn() {
        List<Map<String, Object>> outputs = List.of(output(List.of("Hello, I am the support agent.")),
                mailOutput("<a@x>", List.of(" Your order shipped. ")));
        assertEquals("Hello, I am the support agent.\n\nYour order shipped.", EmailReplyThread.replyText(outputs, 1));
    }

    @Test
    void aLaterMailsReplyIsOnlyItsOwnTurn() {
        List<Map<String, Object>> outputs = List.of(output(List.of("start")), mailOutput("<a@x>", List.of("one")), output(List.of("   ")),
                mailOutput("<b@x>", List.of("two")));
        assertEquals("two", EmailReplyThread.replyText(outputs, 3));
        assertEquals("", EmailReplyThread.replyText(outputs, 4));
        assertEquals("", EmailReplyThread.replyText(outputs, -1));
        assertEquals("", EmailReplyThread.replyText(null, 0));
    }

    @Test
    void textOfReadsStringsTextItemsAndTheirStoredForm() {
        var items = new ArrayList<Object>(List.of("plain", new TextOutputItem("item"), Map.of("text", "stored"), Map.of("type", "image"), 42,
                "  "));
        items.add(new TextOutputItem(null));
        assertEquals("plain\nitem\nstored", EmailReplyThread.textOf(output(items)));
        assertEquals("", EmailReplyThread.textOf(null));
    }

    @Test
    void withoutOutputItemsTheTextKeysAreRead() {
        Map<String, Object> output = new HashMap<>();
        output.put("output", "not-a-list");
        output.put("output:text:greeting", "hi");
        output.put("output:text:list", Arrays.asList("a", Map.of("text", "b"), Map.of("other", "c"), 7));
        output.put("output:text:number", 5);
        output.put("quickReplies", List.of("x"));
        output.put(null, "no key");
        String text = EmailReplyThread.textOf(output);
        // HashMap order is unspecified; compare as a set of lines.
        assertEquals(List.of("a", "b", "hi"), Arrays.stream(text.split("\n")).sorted().toList());
    }

    @Test
    void lastTextIsTheTextOfTheLastOutput() {
        assertEquals("", EmailReplyThread.lastText(null));
        assertEquals("", EmailReplyThread.lastText(List.of()));
        assertEquals("bye", EmailReplyThread.lastText(List.of(output(List.of("hi")), output(List.of("bye")))));
    }
}
