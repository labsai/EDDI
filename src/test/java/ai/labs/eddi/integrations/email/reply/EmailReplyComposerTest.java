/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.reply;

import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.quarkus.qute.Engine;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.MAILBOX;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.MESSAGE_ID;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.SENDER;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.mail;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.settings;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailReplyComposerTest {

    private final EmailReplyComposer composer = new EmailReplyComposer(
            new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build()));

    private static MimeMessage parse(byte[] mime) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(mime));
    }

    @Test
    @DisplayName("the default template is the output, then the AI disclosure")
    void defaultBody() {
        String body = composer.body(settings(), "Your order ships Monday.", mail());
        assertEquals("Your order ships Monday.\n\nThis reply was written by an AI assistant.", body);
        assertEquals("Your order ships Monday.", composer.body(settings(Map.of("aiDisclosure", "")), "Your order ships Monday.", mail()),
                "an emptied disclosure is left out");
    }

    @Test
    @DisplayName("agent output and the subject are data: braces and sections in them are printed, never evaluated")
    void outputIsData() {
        var settings = settings(Map.of("replyTemplate", "Re {mail.subject}:\n{output}\n-- Order desk"));
        Map<String, Object> mail = new HashMap<>(mail());
        mail.put("subject", "{#include secrets/}{vars.apiKey}");
        String body = composer.body(settings, "Total: {properties.price} {#include header /} {#eval output/}", mail);
        assertTrue(
                body.startsWith("Re {#include secrets/}{vars.apiKey}:\nTotal: {properties.price} {#include header /} {#eval output/}\n-- Order desk"),
                body);
    }

    @Test
    @DisplayName("a template that fails falls back to the output; the disclosure is appended as text, not rendered")
    void fallbacks() {
        var broken = settings(Map.of("replyTemplate", "{#if}", "aiDisclosure", "AI {output}"));
        assertEquals("plain\n\nAI {output}", composer.body(broken, "plain", mail()));
    }

    @Test
    @DisplayName("the MIME: threaded to the answered mail, to exactly the recipient, from the mailbox, auto-replied")
    void render() throws Exception {
        var settings = settings(Map.of("fromName", "Contoso Orders"));
        var thread = EmailReplyComposer.threadOf(mail(), MESSAGE_ID);
        var rendered = composer.render(settings, SENDER, thread, "body", "<eddi.0001@contoso.com>");
        MimeMessage message = parse(rendered.mime());

        assertEquals("<eddi.0001@contoso.com>", message.getMessageID());
        assertEquals(1, message.getAllRecipients().length);
        assertEquals(SENDER, message.getAllRecipients()[0].toString());
        assertNull(message.getHeader("Cc"));
        assertTrue(message.getFrom()[0].toString().contains(MAILBOX));
        assertTrue(message.getFrom()[0].toString().contains("Contoso Orders"));
        assertEquals("Re: Order 4711", message.getSubject());
        assertArrayEquals(new String[]{MESSAGE_ID}, message.getHeader("In-Reply-To"));
        String references = String.join(" ", message.getHeader("References")).replaceAll("\\s+", " ");
        assertEquals("<root@supplier.com> " + MESSAGE_ID, references);
        assertArrayEquals(new String[]{"auto-replied"}, message.getHeader("Auto-Submitted"));
        assertTrue(message.getContentType().startsWith("text/plain"));
    }

    @Test
    @DisplayName("a synthetic id is never written into a header, and an invalid reference is dropped")
    void syntheticIds() throws Exception {
        Map<String, Object> mail = new HashMap<>(mail());
        mail.put("references", List.of("garbage", "<ok@supplier.com>"));
        var thread = EmailReplyComposer.threadOf(mail, "sha256:0011");
        assertNull(thread.messageId());
        assertEquals(List.of("<ok@supplier.com>"), thread.references());
        MimeMessage message = parse(composer.render(settings(), SENDER, thread, "body", null).mime());
        assertNull(message.getHeader("In-Reply-To"));
    }

    @Test
    @DisplayName("a subject or recipient that would inject a header is refused by the renderer")
    void headerInjection() {
        var injected = new EmailReplyComposer.Thread("Hi\r\nBcc: victim@example.org", MESSAGE_ID, List.of());
        assertThrows(IllegalArgumentException.class, () -> composer.render(settings(), SENDER, injected, "body", null));
        var fine = EmailReplyComposer.threadOf(mail(), MESSAGE_ID);
        assertThrows(IllegalArgumentException.class, () -> composer.render(settings(), "a@b.example\r\nBcc: c@d.example", fine, "body", null));
        assertFalse(fine.toString().contains("Order"), "no subject in log lines");
    }
}
