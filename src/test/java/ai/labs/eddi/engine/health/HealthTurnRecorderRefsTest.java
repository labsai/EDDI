/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.health.HealthConfigPolicy.Resolved;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.memory.ConversationMemory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthTurnRecorder — business references on the turn row, plain or hashed (H5)")
class HealthTurnRecorderRefsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);
    private static final HealthRefHasher HASHER = HealthRefHasher.withSalt("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private IHealthTurnStore store;
    private HealthConfigPolicy policy;
    private HealthTurnRecorder recorder;

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance(T value) {
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    @BeforeEach
    void setUp() {
        store = mock(IHealthTurnStore.class);
        policy = mock(HealthConfigPolicy.class);
        recorder = new HealthTurnRecorder(instance(store), HealthSettings.defaults(), new HealthMetrics(new SimpleMeterRegistry()), CLOCK);
        recorder.configPolicy = instance(policy);
        recorder.refHasher = instance(HASHER);
    }

    @AfterEach
    void tearDown() {
        recorder.shutdown();
    }

    private static ConversationMemory memory() {
        ConversationMemory memory = new ConversationMemory("conv-1", "agent-1", 2, "user-1");
        memory.getConversationProperties().put("orderId", new Property("orderId", "A-123", Property.Scope.conversation));
        memory.getConversationProperties().put("note", new Property("note", "free text", Property.Scope.conversation));
        return memory;
    }

    private HealthTurn recorded() {
        ArgumentCaptor<HealthTurn> row = ArgumentCaptor.forClass(HealthTurn.class);
        verify(store, timeout(5_000)).upsert(row.capture());
        return row.getValue();
    }

    @Test
    @DisplayName("plain: the declared reference is recorded as name=value, nothing else of the properties")
    void plain() {
        when(policy.of(anyString(), any())).thenReturn(new Resolved(Map.of(), true, Map.of(), false, List.of("orderId"), Map.of(), false));
        recorder.record(memory(), null, null, 10L, false);
        assertEquals(List.of("orderId=A-123"), recorded().refs());
    }

    @Test
    @DisplayName("hashed: the reference is recorded as name#hmac — the value never reaches the row")
    void hashed() {
        when(policy.of(anyString(), any())).thenReturn(new Resolved(Map.of(), true, Map.of(), false, List.of("orderId"), Map.of(), true));
        recorder.record(memory(), null, null, 10L, false);
        List<String> refs = recorded().refs();
        assertEquals(1, refs.size());
        assertTrue(refs.get(0).startsWith("orderId#"));
        assertFalse(refs.get(0).contains("A-123"));
        assertEquals(HealthRefs.stored(Map.of("orderId", "A-123"), true, HASHER), refs);
    }

    @Test
    @DisplayName("an agent without externalRefs records none")
    void none() {
        when(policy.of(anyString(), any())).thenReturn(Resolved.DEFAULTS);
        recorder.record(memory(), null, null, 10L, false);
        assertTrue(recorded().refs().isEmpty());
    }
}
