/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.reply;

import ai.labs.eddi.engine.events.HitlPauseCommittedEvent;
import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailHitlResumeObserverTest {

    @Test
    @DisplayName("pauses and resumes are handed to the reply sender; its failures never escape")
    void delegates() {
        EmailReplySender replies = mock(EmailReplySender.class);
        var observer = new EmailHitlResumeObserver(replies);

        observer.onPauseCommitted(new HitlPauseCommittedEvent("conv-1", "1700000000000"));
        observer.onResumeCompleted(new HitlResumeCompletedEvent("conv-1", HitlVerdict.APPROVED, "approver", null));

        verify(replies).onPauseCommitted(eq("conv-1"), eq("1700000000000"), any());
        verify(replies).onResumeCompleted(eq("conv-1"), eq(HitlVerdict.APPROVED), any());

        when(replies.onPauseCommitted(any(), any(), any())).thenThrow(new IllegalStateException("store down"));
        assertDoesNotThrow(() -> observer.onPauseCommitted(new HitlPauseCommittedEvent("conv-1", "1")));
    }
}
