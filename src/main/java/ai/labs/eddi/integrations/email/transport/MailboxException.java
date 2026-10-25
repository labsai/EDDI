/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.transport;

/**
 * A mailbox could not be read. The message names the host and the cause, never
 * a credential or a resolved address; the category is fit for a metric tag.
 */
public class MailboxException extends Exception {

    /** Why — a bounded set. */
    public enum Category {
        /** The socket policy refused the host or port. */
        POLICY,
        /** The connection's credential could not be resolved. */
        CREDENTIAL,
        /** The server refused the login. */
        AUTHENTICATION,
        /** Connecting, TLS or the protocol exchange failed. */
        CONNECTION,
        /** The folder does not exist or cannot be opened for writing. */
        FOLDER,
        /** A single message could not be fetched. */
        FETCH,
        /** A message turned out larger than the limit while it was fetched. */
        TOO_LARGE
    }

    private final Category category;

    public MailboxException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category getCategory() {
        return category;
    }
}
