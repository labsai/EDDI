/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.reply;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.mail.MailAddress;
import ai.labs.eddi.engine.mail.MessageIds;
import ai.labs.eddi.engine.mail.OutboundMail;
import ai.labs.eddi.engine.mail.PlainTextMailRenderer;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Renders a conversation-mode reply (email plan §15): the body is the author's
 * {@code replyTemplate} with the agent output as data, then the
 * {@code aiDisclosure}; the message is {@code text/plain}, threaded
 * ({@code In-Reply-To}, {@code References}, {@code Re:} subject) and marked
 * {@code Auto-Submitted: auto-replied}, from the integration's address.
 * <p>
 * <b>Output is data.</b> The template is configuration; the agent output and
 * the mail's subject are values substituted into it by the restricted runtime
 * engine, which never parses a value as a template — a {@code {…}} or
 * {@code {#include}} the model writes, or the sender put in a subject, comes
 * out as those characters. A template that fails falls back to the output
 * alone, joined in Java. The disclosure is appended as text, not rendered.
 */
@ApplicationScoped
public class EmailReplyComposer {

    private static final Logger LOGGER = Logger.getLogger(EmailReplyComposer.class);

    /** The template variable the agent output is bound to. */
    static final String OUTPUT_VARIABLE = "output";

    private final ITemplatingEngine templatingEngine;

    @Inject
    public EmailReplyComposer(ITemplatingEngine templatingEngine) {
        this.templatingEngine = templatingEngine;
    }

    /**
     * What a reply threads onto.
     *
     * @param subject
     *            the answered mail's subject, as parsed
     * @param messageId
     *            its RFC Message-ID, or null when it had none
     * @param references
     *            its References, oldest first
     */
    public record Thread(String subject, String messageId, List<String> references) {
        public Thread {
            references = references == null ? List.of() : List.copyOf(references);
        }

        @Override
        public String toString() {
            return "Thread[references=" + references.size() + "]";
        }
    }

    /**
     * The thread of a mail step's map; {@code inboundMessageId} is the ledger's
     * (the step must already have been matched to it). A synthetic id — the mail
     * had none — is never written into a header.
     */
    public static Thread threadOf(Map<String, Object> stepMail, String inboundMessageId) {
        String subject = stepMail != null && stepMail.get("subject") instanceof String s ? s : "";
        List<String> references = new ArrayList<>();
        if (stepMail != null && stepMail.get("references") instanceof List<?> list) {
            for (Object id : list) {
                if (id instanceof String s && MessageIds.isValid(s)) {
                    references.add(s);
                }
            }
        }
        String messageId = inboundMessageId != null && MessageIds.isValid(inboundMessageId) ? inboundMessageId : null;
        return new Thread(subject, messageId, references);
    }

    /**
     * The body: {@code replyTemplate} over {@code {output}} (and the answered
     * mail's {@code {mail.subject}}, {@code {mail.from}}, {@code {mail.fromName}},
     * {@code {mail.date}}), then the disclosure after a blank line.
     */
    public String body(EmailChannelSettings settings, String output, Map<String, Object> stepMail) {
        String text = output == null ? "" : output.strip();
        String template = settings.replyTemplate();
        String rendered;
        if (template == null || template.isBlank() || EmailChannelSettings.DEFAULT_REPLY_TEMPLATE.equals(template.strip())) {
            rendered = text;
        } else {
            Map<String, Object> mail = new LinkedHashMap<>();
            if (stepMail != null) {
                for (String key : List.of("subject", "from", "fromName", "date")) {
                    mail.put(key, stepMail.get(key));
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(OUTPUT_VARIABLE, text);
            data.put("mail", mail);
            try {
                rendered = templatingEngine.processTemplate(template, data, ITemplatingEngine.TemplateMode.TEXT);
                if (rendered == null || rendered.isBlank()) {
                    rendered = text;
                }
            } catch (Exception e) {
                LOGGER.warnf("Email replyTemplate could not be rendered — sending the output as it is: %s", sanitize(e.getMessage()));
                rendered = text;
            }
        }
        String disclosure = settings.aiDisclosure();
        if (disclosure != null && !disclosure.isBlank()) {
            rendered = rendered.stripTrailing() + "\n\n" + disclosure.strip();
        }
        return rendered;
    }

    /**
     * The reply as MIME, to exactly {@code recipient}, under the Message-ID the
     * ledger row was given.
     *
     * @throws IllegalArgumentException
     *             when a header value is unusable (the renderer refuses header
     *             injection)
     */
    public PlainTextMailRenderer.RenderedMail render(EmailChannelSettings settings, String recipient, Thread thread, String body,
                                                     String messageId) {
        MailAddress from = new MailAddress(settings.address(), settings.fromName());
        OutboundMail mail = OutboundMail.replyInThread(from, new MailAddress(recipient, null), thread.subject(), thread.messageId(),
                thread.references(), body, messageId);
        return PlainTextMailRenderer.render(mail);
    }
}
