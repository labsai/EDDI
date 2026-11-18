/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.AttachmentShareService.Refusal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * The share authorization matrix (plan §3.4): who shares (the file's owner, a
 * grantee of it, a stranger to it) × how the file stands in the sharing
 * conversation (listed, withheld by an input guardrail, or taken from another
 * conversation's listing) × share depth (below the limit, at it).
 * <p>
 * The table below is the single readable source of the expected results. Ids
 * are 24-hex strings; refusals are checked with
 * {@code verify(store, never()).grantAccess(any(), any())}.
 * <p>
 * Later work packages extend it: 1.2 (a target this conversation did not
 * start), 2a.5a (a child of a withheld root, part selectors, cross-split
 * delivery), 3.4 (HUMAN outsider), 7.3 (egress).
 */
class AttachmentShareAuthorizationMatrixTest {

    private static final String OWNER_CONV = "65f0a1b2c3d4e5f6a7b8c901";
    private static final String GRANTEE_CONV = "65f0a1b2c3d4e5f6a7b8c902";
    private static final String STRANGER_CONV = "65f0a1b2c3d4e5f6a7b8c903";
    private static final String TARGET_CONV = "65f0a1b2c3d4e5f6a7b8c904";
    private static final String FILE = "6560a1b2c3d4e5f6a7b8c9a0";

    private static final int MAX_DEPTH = 3;

    enum Actor {
        OWNER(OWNER_CONV), GRANTEE(GRANTEE_CONV), STRANGER(STRANGER_CONV);

        final String conversationId;

        Actor(String conversationId) {
            this.conversationId = conversationId;
        }
    }

    enum FileState {
        /** In the sharing conversation's own listing. */
        LISTED,
        /** Withheld by the sharing conversation: absent from its listing. */
        WITHHELD,
        /** Selector taken from the owner's listing, not the sharer's. */
        FOREIGN_LISTING
    }

    enum Depth {
        BELOW(MAX_DEPTH - 1), AT_LIMIT(MAX_DEPTH);

        final int value;

        Depth(int value) {
            this.value = value;
        }
    }

    /** {@code null} expected refusal = shared. */
    static Stream<Arguments> matrix() {
        return Stream.of(
                // actor, file state, depth, expected refusal
                Arguments.of(Actor.OWNER, FileState.LISTED, Depth.BELOW, null),
                Arguments.of(Actor.OWNER, FileState.LISTED, Depth.AT_LIMIT, Refusal.DEPTH),
                Arguments.of(Actor.OWNER, FileState.WITHHELD, Depth.BELOW, Refusal.NOT_FOUND),
                Arguments.of(Actor.OWNER, FileState.WITHHELD, Depth.AT_LIMIT, Refusal.NOT_FOUND),
                Arguments.of(Actor.GRANTEE, FileState.LISTED, Depth.BELOW, null),
                Arguments.of(Actor.GRANTEE, FileState.LISTED, Depth.AT_LIMIT, Refusal.DEPTH),
                Arguments.of(Actor.GRANTEE, FileState.WITHHELD, Depth.BELOW, Refusal.NOT_FOUND),
                Arguments.of(Actor.GRANTEE, FileState.FOREIGN_LISTING, Depth.BELOW, null),
                Arguments.of(Actor.STRANGER, FileState.LISTED, Depth.BELOW, Refusal.NOT_FOUND),
                Arguments.of(Actor.STRANGER, FileState.LISTED, Depth.AT_LIMIT, Refusal.NOT_FOUND),
                Arguments.of(Actor.STRANGER, FileState.FOREIGN_LISTING, Depth.BELOW, Refusal.NOT_FOUND),
                Arguments.of(Actor.STRANGER, FileState.FOREIGN_LISTING, Depth.AT_LIMIT, Refusal.NOT_FOUND));
    }

    @ParameterizedTest(name = "{0} × {1} × depth {2} → {3}")
    @MethodSource("matrix")
    void share(Actor actor, FileState state, Depth depth, Refusal expected) throws Exception {
        var raw = new InMemoryAttachmentStore().put(FILE, "contract.pdf", "application/pdf", 2048, OWNER_CONV);
        raw.grantAccess(FILE, GRANTEE_CONV);
        var store = spy(raw);
        var service = new AttachmentShareService(store, null, null);
        String source = actor.conversationId;

        ConversationListing listing = switch (state) {
            case LISTED -> ConversationListing.of(source, store, Set.of(), List.of());
            case WITHHELD -> ConversationListing.of(source, store, Set.of(FILE), List.of());
            case FOREIGN_LISTING -> ConversationListing.of(OWNER_CONV, store, Set.of(), List.of());
        };
        var policy = new SharePolicy(5, 10, MAX_DEPTH, depth.value, Set.of(), null);

        ShareResult result = service.share(source, listing, List.of(AttachmentSelector.wholeFile(FILE)), TARGET_CONV,
                ShareReason.DELEGATION, policy, ShareParties.UNKNOWN);

        if (expected == null) {
            assertEquals(1, result.shared().size(), result.toString());
            assertTrue(result.refused().isEmpty());
            verify(store).grantAccess(FILE, TARGET_CONV);
            assertTrue(store.isGranted(FILE, TARGET_CONV));
        } else {
            assertTrue(result.shared().isEmpty(), result.toString());
            assertEquals(expected.text(), result.refused().get(0).reason());
            verify(store, never()).grantAccess(any(), any());
            assertFalse(store.isGranted(FILE, TARGET_CONV));
        }
    }

    /**
     * A grantee that shares onward makes the target a grantee of the root; the
     * target, one hop deeper, can share it on again until the depth limit.
     */
    @ParameterizedTest(name = "chain of {0} hops")
    @MethodSource("chains")
    void chain(int hops, boolean lastHopShares) {
        var store = new InMemoryAttachmentStore().put(FILE, "contract.pdf", "application/pdf", 2048, OWNER_CONV);
        var service = new AttachmentShareService(store, null, null);
        String source = OWNER_CONV;
        boolean shared = true;
        for (int depth = 0; depth < hops; depth++) {
            String target = String.format("65f0a1b2c3d4e5f6a7b8%04x", 0xa000 + depth);
            var listing = ConversationListing.of(source, store, Set.of(), List.of());
            var result = service.share(source, listing, List.of(AttachmentSelector.wholeFile(FILE)), target, ShareReason.DELEGATION,
                    new SharePolicy(5, 10, MAX_DEPTH, depth, Set.of(), null), ShareParties.UNKNOWN);
            shared = !result.shared().isEmpty();
            if (!shared) {
                break;
            }
            source = target;
        }
        assertEquals(lastHopShares, shared);
    }

    static Stream<Arguments> chains() {
        return Stream.of(Arguments.of(MAX_DEPTH, true), Arguments.of(MAX_DEPTH + 1, false));
    }
}
