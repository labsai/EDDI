/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HitlCrashRecoveryObserver}.
 */
@DisplayName("HitlCrashRecoveryObserver")
class HitlCrashRecoveryObserverTest {

    private static final String CONV_ID_1 = "conv-stale-1";
    private static final String CONV_ID_2 = "conv-fresh-2";
    private static final String GC_ID_1 = "gc-stale-1";

    @Nested
    @DisplayName("Disabled via config")
    class DisabledTests {

        @Test
        @DisplayName("does nothing when enabled=false")
        void noOpWhenDisabled() throws Exception {
            var memStore = mock(IConversationMemoryStore.class);
            var gcStore = mock(IGroupConversationStore.class);

            var observer = new HitlCrashRecoveryObserver(memStore, gcStore, false, "PT24H");
            observer.onStartup(new StartupEvent());

            verifyNoInteractions(memStore);
            verifyNoInteractions(gcStore);
        }
    }

    @Nested
    @DisplayName("Regular conversation recovery")
    class RegularRecoveryTests {

        @Test
        @DisplayName("transitions stale AWAITING_HUMAN conversations to ERROR")
        void recoversStaleConversation() throws Exception {
            var memStore = mock(IConversationMemoryStore.class);
            var gcStore = mock(IGroupConversationStore.class);

            when(memStore.findConversationIdsByState(ConversationState.AWAITING_HUMAN))
                    .thenReturn(List.of(CONV_ID_1));

            var snapshot = new ConversationMemorySnapshot();
            snapshot.setHitlPausedAt(Instant.now().minus(48, ChronoUnit.HOURS));
            when(memStore.loadConversationMemorySnapshot(CONV_ID_1)).thenReturn(snapshot);
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL))
                    .thenReturn(List.of());

            var observer = new HitlCrashRecoveryObserver(memStore, gcStore, true, "PT24H");
            observer.onStartup(new StartupEvent());

            verify(memStore).setConversationState(CONV_ID_1, ConversationState.ERROR);
        }

        @Test
        @DisplayName("does NOT transition fresh AWAITING_HUMAN conversations")
        void leavesFreshtConversation() throws Exception {
            var memStore = mock(IConversationMemoryStore.class);
            var gcStore = mock(IGroupConversationStore.class);

            when(memStore.findConversationIdsByState(ConversationState.AWAITING_HUMAN))
                    .thenReturn(List.of(CONV_ID_2));

            var snapshot = new ConversationMemorySnapshot();
            snapshot.setHitlPausedAt(Instant.now().minus(1, ChronoUnit.HOURS)); // only 1 hour ago
            when(memStore.loadConversationMemorySnapshot(CONV_ID_2)).thenReturn(snapshot);
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL))
                    .thenReturn(List.of());

            var observer = new HitlCrashRecoveryObserver(memStore, gcStore, true, "PT24H");
            observer.onStartup(new StartupEvent());

            verify(memStore, never()).setConversationState(anyString(), any());
        }

        @Test
        @DisplayName("handles null snapshot gracefully")
        void handlesNullSnapshot() throws Exception {
            var memStore = mock(IConversationMemoryStore.class);
            var gcStore = mock(IGroupConversationStore.class);

            when(memStore.findConversationIdsByState(ConversationState.AWAITING_HUMAN))
                    .thenReturn(List.of(CONV_ID_1));
            when(memStore.loadConversationMemorySnapshot(CONV_ID_1)).thenReturn(null);
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL))
                    .thenReturn(List.of());

            var observer = new HitlCrashRecoveryObserver(memStore, gcStore, true, "PT24H");
            observer.onStartup(new StartupEvent()); // should not throw

            verify(memStore, never()).setConversationState(anyString(), any());
        }
    }

    @Nested
    @DisplayName("Group conversation recovery")
    class GroupRecoveryTests {

        @Test
        @DisplayName("transitions stale AWAITING_APPROVAL group conversations to FAILED")
        void recoversStaleGroupConversation() throws Exception {
            var memStore = mock(IConversationMemoryStore.class);
            var gcStore = mock(IGroupConversationStore.class);

            when(memStore.findConversationIdsByState(ConversationState.AWAITING_HUMAN))
                    .thenReturn(List.of());

            var gc = new GroupConversation();
            gc.setId(GC_ID_1);
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAt(Instant.now().minus(48, ChronoUnit.HOURS));
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL))
                    .thenReturn(List.of(gc));

            var observer = new HitlCrashRecoveryObserver(memStore, gcStore, true, "PT24H");
            observer.onStartup(new StartupEvent());

            verify(gcStore).update(gc);
            // gc.getState() should be FAILED after recovery
            assert gc.getState() == GroupConversationState.FAILED;
        }

        @Test
        @DisplayName("does NOT transition fresh group conversations")
        void leavesFreshGroupConversation() throws Exception {
            var memStore = mock(IConversationMemoryStore.class);
            var gcStore = mock(IGroupConversationStore.class);

            when(memStore.findConversationIdsByState(ConversationState.AWAITING_HUMAN))
                    .thenReturn(List.of());

            var gc = new GroupConversation();
            gc.setId(GC_ID_1);
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAt(Instant.now().minus(1, ChronoUnit.HOURS)); // fresh
            when(gcStore.findByState(GroupConversationState.AWAITING_APPROVAL))
                    .thenReturn(List.of(gc));

            var observer = new HitlCrashRecoveryObserver(memStore, gcStore, true, "PT24H");
            observer.onStartup(new StartupEvent());

            verify(gcStore, never()).update(any());
        }
    }
}
