/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

/**
 * One address a conversation-mode email integration no longer replies to, as
 * {@code GET /channelstore/channels/{id}/email-suppressions} lists it. The
 * address itself is not stored, only its hash and domain.
 *
 * @param addressHash
 *            {@code sha256:<hex>} of the lower-cased address
 * @param domain
 *            the address's domain
 * @param reason
 *            why, e.g. {@code bounce:5.1.1}
 * @param createdAt
 *            ISO-8601 instant
 */
public record EmailSuppressionView(String addressHash, String domain, String reason, String createdAt) {

    /**
     * The body of {@code POST …/email-suppressions/clear}: the address to mail
     * again. A body, not a query parameter, so the address stays out of access
     * logs.
     */
    public record ClearRequest(String address) {
    }
}
