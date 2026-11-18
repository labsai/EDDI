/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import java.util.List;

/**
 * Names resolved against a {@link ConversationListing} by
 * {@link AttachmentShareService#resolveNames}.
 *
 * @param resolved
 *            the entries the names resolved to, in request order, each at most
 *            once
 * @param refused
 *            the names that did not resolve, with a reason the model may read
 * @since 6.7.0
 */
public record Resolution(List<ConversationListing.Entry> resolved, List<ShareResult.Refused> refused) {

    public Resolution {
        resolved = resolved == null ? List.of() : List.copyOf(resolved);
        refused = refused == null ? List.of() : List.copyOf(refused);
    }

    /** The selectors of the resolved entries, ready for a share. */
    public List<AttachmentSelector> selectors() {
        return resolved.stream().map(ConversationListing.Entry::selector).toList();
    }
}
