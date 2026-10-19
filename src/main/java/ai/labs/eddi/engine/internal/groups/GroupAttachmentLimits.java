/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.engine.api.IRestGroupConversation;
import ai.labs.eddi.engine.api.IRestGroupConversation.AttachmentRef;

import java.util.List;

/**
 * The REST-edge re-check of a group discussion's inline attachments against the
 * same limits a 1:1 upload is held to: {@code eddi.attachments.max-size-bytes}
 * per file, and {@code eddi.attachments.max-per-conversation} /
 * {@code max-total-bytes-per-conversation} for the discussion as a whole.
 * <p>
 * The request body itself is already bounded before it is read
 * ({@code RequestBodyLimitGuard}), and {@code AttachmentRef.data} carries a
 * Bean Validation ceiling. This check exists for the reason
 * {@code RestGroupConversation} re-checks its other ceilings by hand — nothing
 * proves the interface-level constraints are wired on the implementation — and
 * because no annotation can express the per-discussion totals. It runs before
 * anything is decoded or stored, on the base64 lengths alone, so a request the
 * store would refuse half-way is refused whole and leaves nothing behind.
 * <p>
 * The per-user quota cannot be judged here (it depends on what the user already
 * stores); the blob store enforces it atomically when the discussion
 * materializes its attachments, and a refusal there surfaces as
 * {@code GroupAttachmentRejectedException}.
 */
public final class GroupAttachmentLimits {

    private GroupAttachmentLimits() {
    }

    /**
     * @param maxFileBytes
     *            {@code eddi.attachments.max-size-bytes}; non-positive disables
     * @param maxPerConversation
     *            {@code eddi.attachments.max-per-conversation}; non-positive
     *            disables
     * @param maxTotalBytesPerConversation
     *            {@code eddi.attachments.max-total-bytes-per-conversation};
     *            non-positive disables
     * @return a client-safe rejection reason built from numbers only, or
     *         {@code null} when the attachments are acceptable
     */
    public static String rejectionReason(List<AttachmentRef> refs, long maxFileBytes, int maxPerConversation,
                                         long maxTotalBytesPerConversation) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        if (refs.size() > IRestGroupConversation.MAX_ATTACHMENTS_PER_REQUEST) {
            return "'attachments' must contain at most " + IRestGroupConversation.MAX_ATTACHMENTS_PER_REQUEST + " entries.";
        }
        int inline = 0;
        long totalBytes = 0;
        for (AttachmentRef ref : refs) {
            if (ref == null || ref.data() == null || ref.data().isBlank()) {
                continue;
            }
            inline++;
            long bytes = decodedLength(ref.data());
            if (maxFileBytes > 0 && bytes > maxFileBytes) {
                return "An inline attachment is too large: " + bytes + " bytes exceeds the limit of " + maxFileBytes + ".";
            }
            totalBytes += bytes;
        }
        if (maxPerConversation > 0 && inline > maxPerConversation) {
            return "A discussion may store at most " + maxPerConversation + " inline attachments.";
        }
        if (maxTotalBytesPerConversation > 0 && totalBytes > maxTotalBytesPerConversation) {
            return "The inline attachments total " + totalBytes + " bytes, more than the " + maxTotalBytesPerConversation
                    + " a discussion may store.";
        }
        return null;
    }

    /**
     * The number of bytes a base64 string decodes to, computed from its length and
     * padding without decoding it. Rounds a malformed length up, which can only
     * make the check stricter; the decoder rejects such input later anyway.
     */
    static long decodedLength(String base64) {
        long length = base64.length();
        long padding = 0;
        if (length > 0 && base64.charAt((int) length - 1) == '=') {
            padding++;
            if (length > 1 && base64.charAt((int) length - 2) == '=') {
                padding++;
            }
        }
        return (length + 3) / 4 * 3 - padding;
    }
}
