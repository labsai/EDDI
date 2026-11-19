/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.engine.mail.InboundMail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * What a mail thread is, in one place: the inbound processor keys correlated
 * conversations by it, and the reply sender counts a thread's replies by it
 * (email v2 plan §7.7).
 */
public final class EmailThreads {

    private EmailThreads() {
    }

    /**
     * The first id in {@code References}, else {@code In-Reply-To}, else the
     * message itself. No subjects.
     */
    public static String threadRoot(String messageId, String inReplyTo, List<String> references) {
        if (references != null && !references.isEmpty() && references.get(0) != null) {
            return references.get(0);
        }
        return inReplyTo != null ? inReplyTo : messageId;
    }

    /** {@link #threadRoot(String, String, List)} of a parsed mail. */
    public static String threadRoot(InboundMail mail) {
        return threadRoot(mail.messageId(), mail.inReplyTo(), mail.references());
    }

    /**
     * {@link #threadRoot(String, String, List)} of the processor's {@code mail} map
     * (a conversation step's context); null when it names no id at all.
     */
    public static String threadRoot(Map<String, Object> mail) {
        if (mail == null) {
            return null;
        }
        List<String> references = mail.get("references") instanceof List<?> list
                ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();
        return threadRoot(asString(mail.get("messageId")), asString(mail.get("inReplyTo")), references);
    }

    /**
     * The ledger's thread key: {@code sha256(integrationId + "\n" + threadRoot)},
     * or null without a root.
     */
    public static String threadKey(String integrationId, String threadRoot) {
        if (integrationId == null || threadRoot == null || threadRoot.isBlank()) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest((integrationId + "\n" + threadRoot).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String asString(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
