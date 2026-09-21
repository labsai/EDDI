/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.utils.LogCaptureSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.JetStream;
import io.nats.client.api.PublishAck;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ai.labs.eddi.utils.LogCaptureSupport.assertNoForgedRecordBoundary;
import static ai.labs.eddi.utils.LogCaptureSupport.captureLogsOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CWE-117 regression test for {@link NatsConversationCoordinator}'s publish
 * line (code scanning alert #121).
 *
 * <p>
 * The line logs the NATS <em>subject</em>, not the conversation id, which is
 * why it survived the pass that sanitized the rest of the file. The subject is
 * {@code SUBJECT_PREFIX + sanitizeSubject(conversationId)}, and
 * {@code sanitizeSubject} is not a log sanitizer despite the name: it replaces
 * {@code .} and space because a NATS subject token may not contain them, and it
 * leaves CR and LF — which a subject token may not contain either — untouched.
 * So the caller's conversation id still reaches the log able to end the record.
 * </p>
 *
 * <p>
 * The fix is at the log call rather than inside {@code sanitizeSubject},
 * because widening that method would change the subject namespace every
 * deployment already publishes and consumes under. It is also what the file
 * already does: {@code routeToDeadLetter} logs
 * {@code sanitize(deadLetterSubject)} for exactly this reason.
 * </p>
 */
@DisplayName("NatsConversationCoordinator log injection (CWE-117)")
class NatsConversationCoordinatorLogInjectionTest {

    private NatsConversationCoordinator coordinator;

    /**
     * Injects a mocked {@code JetStream} into the coordinator by reflection — the
     * idiom the sibling coordinator tests already use — so a publish succeeds and
     * reaches the line under test without a broker.
     */
    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        var natsMetrics = mock(NatsMetrics.class);
        when(natsMetrics.getPublishCount()).thenReturn(mock(Counter.class));
        when(natsMetrics.getPublishDuration()).thenReturn(mock(Timer.class));

        Instance<NatsMetrics> metricsInstance = mock(Instance.class);
        when(metricsInstance.isResolvable()).thenReturn(true);
        when(metricsInstance.get()).thenReturn(natsMetrics);

        coordinator = new NatsConversationCoordinator(mock(IRuntime.class), metricsInstance,
                new SimpleMeterRegistry(), "nats://localhost:4222",
                "EDDI_CONVERSATIONS", "EDDI_DEAD_LETTERS", 3, 10000);

        JetStream jetStream = mock(JetStream.class);
        var jetStreamField = NatsConversationCoordinator.class.getDeclaredField("jetStream");
        jetStreamField.setAccessible(true);
        jetStreamField.set(coordinator, jetStream);

        PublishAck publishAck = mock(PublishAck.class);
        when(publishAck.getSeqno()).thenReturn(1L);
        when(jetStream.publish(anyString(), any(byte[].class))).thenReturn(publishAck);
    }

    /**
     * The caller's conversation id reaches the publish line through the subject it
     * is built into, so the forgery travels one step further than on the other
     * sinks in this change.
     */
    @Test
    @DisplayName("a forged conversationId cannot forge a record on the publish line")
    void sanitizesTheSubjectOnThePublishLine() {
        // The queue is empty for a conversation id never seen before, so the very
        // first submission is the one that publishes.
        String forgedConversationId = "conv-1" + LogCaptureSupport.FORGED_RECORD;

        List<String> logged = captureLogsOf(NatsConversationCoordinator.class,
                () -> coordinator.submitInOrder(forgedConversationId, () -> null));

        assertNoForgedRecordBoundary(logged, "the NATS publish line of NatsConversationCoordinator");
        assertTrue(logged.stream().anyMatch(value -> value.contains("eddi.conversation.conv-1")),
                "the subject is sanitized, not dropped — an operator still needs to know which subject: " + logged);
    }

    /**
     * Pins the premise of the test above, so the reason the fix sits at the log
     * call cannot quietly stop being true.
     */
    @Test
    @DisplayName("sanitizeSubject is a NATS token rule, not a log sanitizer")
    void sanitizeSubjectDoesNotRemoveRecordBoundaries() {
        // Pins the premise of the test above: if sanitizeSubject is ever widened to
        // strip CR/LF, this fails and whoever widened it can decide, deliberately,
        // whether the sanitize(...) at the log call is now redundant — rather than
        // the two silently drifting apart.
        String subject = coordinator.sanitizeSubject("conv-1" + LogCaptureSupport.FORGED_RECORD);

        assertTrue(subject.indexOf('\n') >= 0,
                "sanitizeSubject is documented as replacing dots and spaces only: " + subject);
        assertFalse(subject.contains("."),
                "sanitizeSubject still has to make a valid NATS token: " + subject);
    }
}
