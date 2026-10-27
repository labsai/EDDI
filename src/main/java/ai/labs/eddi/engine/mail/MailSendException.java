/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

/**
 * A message the {@link SystemMailer} could not hand to the relay.
 * <p>
 * The message names the category and the relay's own words, never a recipient
 * address or the body. {@link #isPermanent()} is true only when the relay
 * refused the message or recipient with a 5xx reply — retrying cannot change
 * that answer; everything else (connect, TLS, authentication, a 4xx) may be
 * transient and is retried by the caller.
 */
public class MailSendException extends Exception {

    /** Why the send failed — a bounded category, fit for a metric tag or a log. */
    public enum Category {
        /** The mailer is not configured or its configuration is unusable. */
        NOT_CONFIGURED,
        /** The relay host was refused by the socket policy or does not resolve. */
        SOCKET_POLICY,
        /** No TCP connection, or it broke. */
        CONNECT,
        /** TLS or STARTTLS failed, including a relay that does not offer STARTTLS. */
        TLS,
        /** The credential could not be resolved, or the relay rejected it. */
        AUTHENTICATION,
        /** The relay refused the sender, a recipient or the message. */
        REJECTED,
        /** Anything else. */
        OTHER
    }

    private final Category category;
    private final boolean permanent;

    public MailSendException(Category category, boolean permanent, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
        this.permanent = permanent;
    }

    public Category getCategory() {
        return category;
    }

    /** Whether retrying the same message to the same relay cannot succeed. */
    public boolean isPermanent() {
        return permanent;
    }
}
