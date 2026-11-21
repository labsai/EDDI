/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.modules.llm.tools.TurnCounter;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The limits one {@link AttachmentShareService#share} call applies. The caller
 * builds it from the effective configuration (designer value, operator default,
 * operator bound); the service enforces it and never reads configuration
 * itself.
 *
 * @param maxFilesPerShare
 *            how many files this one call may share
 * @param maxSharesPerTurn
 *            how many files the conversation may share in its current turn,
 *            across every call
 * @param maxDepth
 *            how many hops a file may travel; a share is refused once
 *            {@code currentDepth} has reached it
 * @param currentDepth
 *            how many hops the source conversation is from the user — 0 for a
 *            conversation the user drives
 * @param allowedMimeTypes
 *            the MIME types that may be shared; empty (or {@code null}) means
 *            any. Compared case-insensitively
 * @param sharesThisTurn
 *            the turn's share count, kept on the conversation's current step
 *            ({@link TurnCounter#onStep}). {@code null} is replaced by a
 *            counter local to this policy, so {@code maxSharesPerTurn} then
 *            bounds the calls made with this policy instance
 * @since 6.7.0
 */
public record SharePolicy(int maxFilesPerShare, int maxSharesPerTurn, int maxDepth, int currentDepth,
        Set<String> allowedMimeTypes, TurnCounter sharesThisTurn) {

    public SharePolicy {
        allowedMimeTypes = allowedMimeTypes == null
                ? Set.of()
                // A null entry becomes "", which matches no stored (validated) MIME type:
                // dropping it instead could empty the set and so allow every type.
                : allowedMimeTypes.stream().map(m -> m == null ? "" : m.strip().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        if (sharesThisTurn == null) {
            sharesThisTurn = TurnCounter.local();
        }
    }

    /** Whether {@code mimeType} may be shared under this policy. */
    public boolean allowsMimeType(String mimeType) {
        return allowedMimeTypes.isEmpty()
                || mimeType != null && !mimeType.isBlank() && allowedMimeTypes.contains(mimeType.strip().toLowerCase(Locale.ROOT));
    }
}
