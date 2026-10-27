/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

/**
 * The header values that say a message was sent by a machine, captured raw
 * (unfolded, trimmed, length-capped) so {@link AutoReplyDetector} — and a later
 * loop guard with more context — can judge them.
 *
 * @param autoSubmitted
 *            {@code Auto-Submitted} (RFC 3834)
 * @param xAutoreply
 *            {@code X-Autoreply}
 * @param xAutorespond
 *            {@code X-Autorespond}
 * @param precedence
 *            {@code Precedence}
 * @param listId
 *            {@code List-Id}
 * @param listUnsubscribe
 *            {@code List-Unsubscribe}
 * @param returnPath
 *            {@code Return-Path}, as the final MTA wrote it
 * @param reportType
 *            the {@code report-type} of a top-level or nested
 *            {@code multipart/report}, or null when there is none
 */
public record LoopSignals(String autoSubmitted, String xAutoreply, String xAutorespond, String precedence, String listId,
        String listUnsubscribe, String returnPath, String reportType) {

    /** No signal at all. */
    public static LoopSignals none() {
        return new LoopSignals(null, null, null, null, null, null, null, null);
    }
}
