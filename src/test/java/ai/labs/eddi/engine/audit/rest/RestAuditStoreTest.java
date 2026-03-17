package ai.labs.eddi.engine.audit.rest;

import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestAuditStore} — verifies correct delegation to {@link IAuditStore}.
 */
class RestAuditStoreTest {

    private IAuditStore auditStore;
    private RestAuditStore restAuditStore;

    private AuditEntry sampleEntry() {
        return new AuditEntry(
                "id-1", "conv-1", "bot-1", 1, "user-1", "unrestricted",
                0, "task-1", "test-type", 0, 42L,
                Map.of("userInput", "hello"), Map.of("output", List.of("world")),
                null, null, List.of("greet"), 0.0, Instant.now(), "hmac-abc");
    }

    @BeforeEach
    void setUp() {
        auditStore = mock(IAuditStore.class);
        restAuditStore = new RestAuditStore(auditStore);
    }

    @Test
    @DisplayName("getAuditTrail delegates to auditStore.getEntries")
    void getAuditTrail_delegatesToStore() {
        var expected = List.of(sampleEntry());
        when(auditStore.getEntries("conv-1", 5, 50)).thenReturn(expected);

        var result = restAuditStore.getAuditTrail("conv-1", 5, 50);

        assertEquals(expected, result);
        verify(auditStore).getEntries("conv-1", 5, 50);
    }

    @Test
    @DisplayName("getAuditTrailByBot delegates to auditStore.getEntriesByBot")
    void getAuditTrailByBot_delegatesToStore() {
        var expected = List.of(sampleEntry());
        when(auditStore.getEntriesByBot("bot-1", 1, 0, 100)).thenReturn(expected);

        var result = restAuditStore.getAuditTrailByBot("bot-1", 1, 0, 100);

        assertEquals(expected, result);
        verify(auditStore).getEntriesByBot("bot-1", 1, 0, 100);
    }

    @Test
    @DisplayName("getAuditTrailByBot with null version delegates correctly")
    void getAuditTrailByBot_nullVersion_delegatesToStore() {
        when(auditStore.getEntriesByBot("bot-1", null, 0, 100)).thenReturn(List.of());

        var result = restAuditStore.getAuditTrailByBot("bot-1", null, 0, 100);

        assertEquals(0, result.size());
        verify(auditStore).getEntriesByBot("bot-1", null, 0, 100);
    }

    @Test
    @DisplayName("getEntryCount delegates to auditStore.countByConversation")
    void getEntryCount_delegatesToStore() {
        when(auditStore.countByConversation("conv-1")).thenReturn(42L);

        long count = restAuditStore.getEntryCount("conv-1");

        assertEquals(42L, count);
        verify(auditStore).countByConversation("conv-1");
    }
}
