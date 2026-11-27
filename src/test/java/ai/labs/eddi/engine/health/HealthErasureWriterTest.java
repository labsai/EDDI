/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthErasureWriter — Agent Health's async writer of the erasure guarantee")
class HealthErasureWriterTest {

    private final HealthTurnRecorder recorder = mock(HealthTurnRecorder.class);
    private final IHealthTurnStore store = mock(IHealthTurnStore.class);
    private final HealthErasureWriter writer = new HealthErasureWriter(recorder, store);

    @Test
    @DisplayName("removeRow deletes exactly the row its id names")
    void removeRow() {
        String rowId = HealthErasureWriter.rowId("65a1b2c3d4e5f6a7b8c9d0e1", "6f1c2d3e-aaaa-bbbb-cccc-0123456789ab");
        writer.removeRow(rowId);
        verify(store).deleteRow("65a1b2c3d4e5f6a7b8c9d0e1", "6f1c2d3e-aaaa-bbbb-cccc-0123456789ab");

        writer.removeRow(HealthErasureWriter.rowId("conv", "legacy-3"));
        verify(store).deleteRow("conv", "legacy-3");
    }

    @Test
    @DisplayName("an id that names no row is refused, so its removal stays pending")
    void malformedRowId() {
        assertThrows(IllegalArgumentException.class, () -> writer.removeRow("no-separator"));
        assertThrows(IllegalArgumentException.class, () -> writer.removeRow("|exec"));
        assertThrows(IllegalArgumentException.class, () -> writer.removeRow("conv|"));
        assertThrows(IllegalArgumentException.class, () -> writer.removeRow(null));
    }

    @Test
    @DisplayName("name, drain, conversation delete and count go to the recorder and the store")
    void delegates() throws Exception {
        assertEquals("agentHealthTurns", writer.name());
        when(recorder.awaitDrained(Duration.ofSeconds(1))).thenReturn(true);
        assertTrue(writer.awaitDrained(Duration.ofSeconds(1)));
        when(store.deleteByConversationId("conv")).thenReturn(3L);
        when(store.countByConversationId("conv")).thenReturn(0L);
        assertEquals(3L, writer.eraseConversation("conv"));
        assertEquals(0L, writer.countForConversation("conv"));
    }
}
