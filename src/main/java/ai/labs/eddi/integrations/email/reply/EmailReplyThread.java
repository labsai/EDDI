/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.reply;

import ai.labs.eddi.modules.output.model.OutputItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads what a reply is made of from a conversation's outputs — the same
 * outputs whether they come from a turn's completion callback or from the
 * stored conversation, so a reply rebuilt after a crash is the reply that would
 * have been sent (email plan §12).
 * <p>
 * A mail's step is the output whose {@code context.mail.messageId} is the
 * mail's Message-ID: the inbound processor puts the mail map into the turn's
 * context, so the step carries, in conversation memory, the facts the reply
 * needs — subject, threading ids, the authentication verdict — and the ledger
 * never stores any of them.
 */
public final class EmailReplyThread {

    /** The context key the inbound processor writes the mail map under. */
    static final String MAIL_KEY = "mail";

    private EmailReplyThread() {
    }

    /**
     * The index of the last output that carries the mail {@code messageId}, or -1.
     */
    public static int mailStep(List<? extends Map<String, Object>> outputs, String messageId) {
        if (outputs == null || messageId == null) {
            return -1;
        }
        for (int i = outputs.size() - 1; i >= 0; i--) {
            Map<String, Object> mail = mailOf(outputs.get(i));
            if (mail != null && messageId.equals(mail.get("messageId"))) {
                return i;
            }
        }
        return -1;
    }

    /** The mail map of one output's context, or null. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mailOf(Map<String, Object> output) {
        if (output == null || !(output.get("context") instanceof Map<?, ?> context)) {
            return null;
        }
        Object mail = context.get(MAIL_KEY);
        return mail instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /**
     * The reply to the mail of step {@code step}: the text of every output after
     * the previous mail's step up to and including this one. For the first mail of
     * a conversation that includes what the {@code CONVERSATION_START} turn said;
     * for later mail it is the mail's own turn.
     */
    public static String replyText(List<? extends Map<String, Object>> outputs, int step) {
        if (outputs == null || step < 0 || step >= outputs.size()) {
            return "";
        }
        int from = 0;
        for (int i = step - 1; i >= 0; i--) {
            if (mailOf(outputs.get(i)) != null) {
                from = i + 1;
                break;
            }
        }
        List<String> parts = new ArrayList<>();
        for (int i = from; i <= step; i++) {
            String text = textOf(outputs.get(i));
            if (!text.isBlank()) {
                parts.add(text.strip());
            }
        }
        return String.join("\n\n", parts);
    }

    /**
     * The text of one output: its {@code output} items (strings, text items, or
     * their stored map form), else its {@code output:text:*} keys; empty when it
     * has none. The same two shapes the Slack channel reads.
     */
    public static String textOf(Map<String, Object> output) {
        if (output == null) {
            return "";
        }
        List<String> texts = new ArrayList<>();
        if (output.get("output") instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof String s) {
                    texts.add(s);
                } else if (item instanceof OutputItem outputItem && outputItem.toString() != null) {
                    texts.add(outputItem.toString());
                } else if (item instanceof Map<?, ?> map && map.get("text") instanceof String s) {
                    texts.add(s);
                }
            }
        }
        if (texts.isEmpty()) {
            for (Map.Entry<String, Object> entry : output.entrySet()) {
                if (entry.getKey() != null && entry.getKey().startsWith("output:text:")) {
                    if (entry.getValue() instanceof String s) {
                        texts.add(s);
                    } else if (entry.getValue() instanceof List<?> list) {
                        for (Object item : list) {
                            if (item instanceof String s) {
                                texts.add(s);
                            } else if (item instanceof Map<?, ?> map && map.get("text") instanceof String s) {
                                texts.add(s);
                            }
                        }
                    }
                }
            }
        }
        texts.removeIf(text -> text == null || text.isBlank());
        return String.join("\n", texts);
    }

    /** The text of the last output, or empty. */
    public static String lastText(List<? extends Map<String, Object>> outputs) {
        return outputs == null || outputs.isEmpty() ? "" : textOf(outputs.get(outputs.size() - 1));
    }
}
