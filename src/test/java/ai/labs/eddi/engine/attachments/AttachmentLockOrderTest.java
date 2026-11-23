/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.AttachmentLockOrder.Lock;
import ai.labs.eddi.engine.attachments.AttachmentLockOrder.Scope;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The one lock order, conversation → user → team → source, whatever order a
 * caller lists the locks in.
 */
class AttachmentLockOrderTest {

    private static final Lock CONVERSATION = new Lock(Scope.CONVERSATION, "6aba70011a25f8be7920e001");
    private static final Lock USER = new Lock(Scope.USER, "user-6aba70011a25f8be");
    private static final Lock TEAM = new Lock(Scope.TEAM, "tenant-6aba70011a25f8be");
    private static final Lock SOURCE = new Lock(Scope.SOURCE, "8d4f6f1e-0000-4000-8000-000000000001");

    @Test
    void theOrderIsFixedWhateverOrderTheLocksAreListedIn() {
        List<Lock> expected = List.of(CONVERSATION, USER, TEAM, SOURCE);

        assertEquals(expected, AttachmentLockOrder.ordered(List.of(SOURCE, TEAM, USER, CONVERSATION)));
        assertEquals(expected, AttachmentLockOrder.ordered(List.of(USER, SOURCE, CONVERSATION, TEAM)));
        assertEquals(expected, AttachmentLockOrder.ordered(expected));
    }

    @Test
    void theScopesKeepTheOrderOlderNodesUse() {
        // Conversation before user is what every release has taken; the scopes added
        // since come after them.
        assertEquals(List.of(Scope.CONVERSATION, Scope.USER, Scope.TEAM, Scope.SOURCE), List.of(Scope.values()));
    }

    @Test
    void aLockWithoutAnIdAndARepeatedLockAreDropped() {
        assertEquals(List.of(CONVERSATION, SOURCE),
                AttachmentLockOrder.ordered(Arrays.asList(SOURCE, new Lock(Scope.USER, null), null, CONVERSATION, SOURCE)));
    }
}
