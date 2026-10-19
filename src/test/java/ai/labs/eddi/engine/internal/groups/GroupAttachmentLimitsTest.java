/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.engine.api.IRestGroupConversation;
import ai.labs.eddi.engine.api.IRestGroupConversation.AttachmentRef;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GroupAttachmentLimitsTest {

    private static AttachmentRef inline(int bytes) {
        return new AttachmentRef("application/octet-stream", Base64.getEncoder().encodeToString(new byte[bytes]), null, "f.bin");
    }

    @Test
    void decodedLength_matchesTheDecoderForEveryPaddingShape() {
        for (int n = 0; n < 20; n++) {
            String encoded = Base64.getEncoder().encodeToString("x".repeat(n).getBytes(StandardCharsets.UTF_8));
            assertEquals(n, GroupAttachmentLimits.decodedLength(encoded), "length " + n);
        }
    }

    @Test
    void withinEveryLimit_isAccepted() {
        assertNull(GroupAttachmentLimits.rejectionReason(List.of(inline(10), inline(10)), 10, 2, 20));
        assertNull(GroupAttachmentLimits.rejectionReason(null, 1, 1, 1));
    }

    @Test
    void oneFileOverThePerFileLimit_isRefused() {
        assertNotNull(GroupAttachmentLimits.rejectionReason(List.of(inline(11)), 10, 50, 1_000));
    }

    @Test
    void moreInlineFilesThanTheConversationMayStore_isRefused_urlsDoNotCount() {
        var url = new AttachmentRef("image/png", null, "https://example.com/a.png", "a.png");
        assertNull(GroupAttachmentLimits.rejectionReason(List.of(inline(1), url, url), 10, 1, 1_000));
        assertNotNull(GroupAttachmentLimits.rejectionReason(List.of(inline(1), inline(1)), 10, 1, 1_000));
    }

    @Test
    void filesTogetherOverTheConversationTotal_isRefused() {
        assertNotNull(GroupAttachmentLimits.rejectionReason(List.of(inline(10), inline(10)), 10, 50, 19));
    }

    @Test
    void nonPositiveLimits_areDisabled() {
        assertNull(GroupAttachmentLimits.rejectionReason(List.of(inline(100), inline(100)), 0, 0, 0));
    }

    @Test
    void moreEntriesThanTheRequestCeiling_isRefused() {
        var many = new ArrayList<AttachmentRef>();
        for (int i = 0; i <= IRestGroupConversation.MAX_ATTACHMENTS_PER_REQUEST; i++) {
            many.add(new AttachmentRef("image/png", null, "https://example.com/" + i + ".png", null));
        }
        assertNotNull(GroupAttachmentLimits.rejectionReason(many, 0, 0, 0));
    }
}
