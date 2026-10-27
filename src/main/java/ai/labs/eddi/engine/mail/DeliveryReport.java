/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.util.List;
import java.util.Locale;

/**
 * The machine-readable part of a {@code multipart/report} delivery status
 * notification (RFC 3464), as far as bounce handling needs it.
 * <p>
 * Parsed so that a later phase can mark the outbound ledger row the bounce
 * refers to ({@link #originalMessageId()}) and suppress addresses that fail
 * permanently ({@link Recipient#isPermanentFailure()}). It is untrusted input
 * like any other mail: anyone can send something shaped like a bounce, so a
 * caller acts on it only for a message id it actually sent.
 *
 * @param reportType
 *            the {@code report-type} parameter, lower-case
 *            ({@code delivery-status})
 * @param reportingMta
 *            {@code Reporting-MTA}, or null
 * @param originalMessageId
 *            the Message-ID of the returned message or headers, or null
 * @param recipients
 *            the per-recipient blocks
 */
public record DeliveryReport(String reportType, String reportingMta, String originalMessageId, List<Recipient> recipients) {

    public DeliveryReport {
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
    }

    /**
     * One per-recipient block.
     *
     * @param finalRecipient
     *            the normalised address from {@code Final-Recipient}, or the raw
     *            value when it is not a valid address
     * @param action
     *            {@code Action}, lower-case ({@code failed}, {@code delayed},
     *            {@code delivered}, …)
     * @param status
     *            {@code Status}, e.g. {@code 5.1.1}
     * @param diagnosticCode
     *            {@code Diagnostic-Code}, capped
     */
    public record Recipient(String finalRecipient, String action, String status, String diagnosticCode) {

        /** A hard bounce: failed with a 5.x.x status. */
        public boolean isPermanentFailure() {
            return "failed".equals(normalized(action)) && status != null && status.strip().startsWith("5");
        }

        /** A soft bounce or delay: 4.x.x, or {@code Action: delayed}. */
        public boolean isTransientFailure() {
            return "delayed".equals(normalized(action)) || status != null && status.strip().startsWith("4");
        }

        private static String normalized(String value) {
            return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
        }
    }
}
