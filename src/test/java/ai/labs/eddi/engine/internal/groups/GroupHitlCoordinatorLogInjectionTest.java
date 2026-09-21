/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.utils.LogCaptureSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import static ai.labs.eddi.utils.LogCaptureSupport.assertNoForgedRecordBoundary;
import static ai.labs.eddi.utils.LogCaptureSupport.captureLogsOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CWE-117 regression tests for {@link GroupHitlCoordinator}'s log lines.
 *
 * <p>
 * A group conversation id reaches these lines from the caller — it is a path
 * parameter on the group REST surface — so a caller that puts a CRLF in one can
 * forge a log record that reads as a genuine, server-authored entry. The same
 * is true of an exception message, which on these paths is routinely the store
 * repeating back text the caller supplied.
 * </p>
 *
 * <p>
 * {@code deleteGroupHitlTimeoutSchedule} is the entry point tested here because
 * it is public and reaches two of the flagged sinks — the success line and the
 * failure line — with one mock and no conversation. The other thirty-six
 * sanitized sinks sit inside a phase loop or a state-race catch that takes a
 * full group discussion to reach; {@code SanitizedLogSinksTest} pins those at
 * the source, and {@code LogRecordBoundaryForgeryTest} covers the half of a log
 * line no call site can reach at all — the throwable.
 * </p>
 */
@DisplayName("GroupHitlCoordinator log injection (CWE-117)")
class GroupHitlCoordinatorLogInjectionTest {

    private IScheduleStore scheduleStore;

    private GroupHitlCoordinator coordinator() {
        scheduleStore = mock(IScheduleStore.class);
        return new GroupHitlCoordinator(mock(IAgentGroupStore.class), mock(IGroupConversationStore.class), scheduleStore,
                mock(AuditLedgerService.class), new GroupSigningGuard(null, null, null, "default"),
                new ConcurrentHashMap<>(), mock(ExecutorService.class), new CallerIdentityContext(null, null),
                mock(GroupConversationService.class),
                new SimpleMeterRegistry().counter("test.hitl.pause"),
                new SimpleMeterRegistry().counter("test.hitl.resume"),
                new SimpleMeterRegistry().counter("test.group.failure"));
    }

    @Test
    @DisplayName("a forged group conversation id cannot forge a record on the cleanup line")
    void sanitizesTheGroupConversationIdOnTheCleanupLine() throws Exception {
        GroupHitlCoordinator coordinator = coordinator();
        when(scheduleStore.deleteSchedulesByName(anyString())).thenReturn(1);
        String forged = "gc-1" + LogCaptureSupport.FORGED_RECORD;

        List<String> logged = captureLogsOf(GroupHitlCoordinator.class,
                () -> coordinator.deleteGroupHitlTimeoutSchedule(forged));

        assertNoForgedRecordBoundary(logged, "GroupHitlCoordinator's timeout-schedule cleanup line");
        assertTrue(logged.stream().anyMatch(value -> value.contains("gc-1")),
                "the id is sanitized, not dropped — an operator still needs to know which conversation: " + logged);
    }

    @Test
    @DisplayName("a forged exception message cannot forge a record on the cleanup failure line")
    void sanitizesTheExceptionMessageOnTheCleanupFailureLine() throws Exception {
        // The store's message is not the developer's text: it routinely quotes the
        // value it was handed, which is the caller's.
        GroupHitlCoordinator coordinator = coordinator();
        when(scheduleStore.deleteSchedulesByName(anyString()))
                .thenThrow(new IllegalStateException("schedule store unavailable" + LogCaptureSupport.FORGED_RECORD));

        List<String> logged = captureLogsOf(GroupHitlCoordinator.class,
                () -> coordinator.deleteGroupHitlTimeoutSchedule("gc-1"));

        assertNoForgedRecordBoundary(logged, "GroupHitlCoordinator's timeout-schedule failure line");
        assertTrue(logged.stream().anyMatch(value -> value.contains("schedule store unavailable")),
                "the reason is sanitized, not dropped — it is the only diagnostic this swallowed exception leaves: " + logged);
    }
}
