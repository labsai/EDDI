/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import java.util.List;

/**
 * One row of an email integration's mail log, as {@code GET
 * /channelstore/channels/{id}/email-log} and {@code POST …/email-log/lookup}
 * return it: what happened to one message and why — never its subject or body
 * (the ledger holds neither).
 *
 * @param id
 *            the ledger row id
 * @param kind
 *            {@code TRIGGER} (an inbound mail, in either mode), {@code REPLY},
 *            {@code NOTIFY} or {@code NOTICE}
 * @param direction
 *            {@code IN} or {@code OUT}
 * @param messageId
 *            the RFC 5322 Message-ID
 * @param state
 *            e.g. {@code DONE}, {@code REFUSED}, {@code SENT}
 * @param reason
 *            the row's last error or refusal reason, e.g.
 *            {@code blocked:not_allowlisted}; null when there is none
 * @param reasonCategory
 *            {@code reason} up to its first {@code :}
 * @param conversationId
 *            the conversation the message belongs to, or null
 * @param counterpart
 *            the sender of an inbound mail, the recipient of an outbound one
 * @param attempts
 *            send attempts so far
 * @param createdAt
 *            ISO-8601 instant
 * @param updatedAt
 *            ISO-8601 instant, or null
 */
public record EmailLogView(String id, String kind, String direction, String messageId, String state, String reason, String reasonCategory,
        String conversationId, String counterpart, int attempts, String createdAt, String updatedAt) {

    /**
     * One page of the mail log, newest first.
     *
     * @param rows
     *            the rows
     * @param nextCursor
     *            pass as {@code after} for the next page; null on the last one
     */
    public record Page(List<EmailLogView> rows, String nextCursor) {
    }

    /**
     * The body of {@code POST …/email-log/lookup}. A body, not a query parameter,
     * so the Message-ID (which usually names a domain) stays out of access logs.
     */
    public record LookupRequest(String messageId) {
    }
}
