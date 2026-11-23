/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import java.time.Instant;
import java.util.List;

/**
 * What the REST and MCP surfaces show of mail held for review (email channel v3
 * plan §7): the queue, a live preview, a decision. No body is ever stored; the
 * preview is fetched from the mailbox each time it is asked for.
 */
public final class EmailHeldMailView {

    private EmailHeldMailView() {
    }

    /**
     * One held mail in the queue.
     *
     * @param rowId
     *            the ledger row to decide
     * @param sender
     *            the From address (authenticated or not)
     * @param authVerdict
     *            the sender authentication verdict when it was held
     * @param heldFor
     *            the reviewable checks that held it ({@code not_allowlisted},
     *            {@code unauthenticated})
     * @param heldAt
     *            when it arrived
     * @param expiresAt
     *            when it is dropped if nobody decides
     * @param runAndAllowOffered
     *            whether {@code runAndAllow} is possible: only for an aligned pass
     * @param runsAnonymously
     *            whether a release runs it anonymously, with read-only tools and no
     *            outgoing mail
     */
    public record Item(String rowId, String integrationId, String sender, String authVerdict, List<String> heldFor, Instant heldAt,
            Instant expiresAt, boolean runAndAllowOffered, boolean runsAnonymously) {
    }

    /** The queue of one integration. */
    public record Queue(String integrationId, String integrationName, List<Item> items, boolean truncated) {
    }

    /**
     * The live preview of a held mail: re-read from the mailbox by its Message-ID
     * and checked against the hash taken when it was held.
     *
     * @param text
     *            the first {@code 4000} characters of the text, quoted history
     *            stripped and hidden content removed
     */
    public record Preview(Item item, String fromName, String subject, String text, boolean textTruncated, List<String> attachments) {
    }

    /**
     * {@code {"decision": "discard" | "runOnce" | "runAndAllow", "note": "..."}}.
     */
    public record DecisionRequest(String decision, String note) {
    }

    /**
     * What a decision did.
     *
     * @param state
     *            the row's state now ({@code RECEIVED} when released,
     *            {@code REFUSED} when discarded)
     * @param allowlistVersion
     *            for {@code runAndAllow}: the integration version that added the
     *            sender, or null
     */
    public record DecisionResult(String rowId, String decision, String state, Integer allowlistVersion, List<String> warnings) {
    }
}
