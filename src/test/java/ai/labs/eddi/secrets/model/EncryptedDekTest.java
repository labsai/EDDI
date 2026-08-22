/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for EncryptedDek — the generation a row holds and the dekId round trip
 * that generation drives.
 */
class EncryptedDekTest {

    private static final String TENANT = "tenant-1";

    @Test
    @DisplayName("constructor — a generation below 1 is stored as 1")
    void constructorNormalizesBelowFirstGeneration() {
        // A row predating the generation column reads back as 0. Keeping the 0 would
        // seal ciphertext under a name that reads back as a different generation.
        assertEquals(EncryptedDek.FIRST_GENERATION, dek(0).getGeneration());
        assertEquals(EncryptedDek.FIRST_GENERATION, dek(-7).getGeneration());
    }

    @Test
    @DisplayName("constructor — a real generation is kept exactly")
    void constructorKeepsRealGenerations() {
        assertEquals(1, dek(1).getGeneration());
        assertEquals(4, dek(4).getGeneration());
    }

    @Test
    @DisplayName("setGeneration — a generation below 1 is stored as 1")
    void setterNormalizesBelowFirstGeneration() {
        EncryptedDek encryptedDek = dek(3);

        encryptedDek.setGeneration(0);
        assertEquals(EncryptedDek.FIRST_GENERATION, encryptedDek.getGeneration());

        encryptedDek.setGeneration(-1);
        assertEquals(EncryptedDek.FIRST_GENERATION, encryptedDek.getGeneration());

        encryptedDek.setGeneration(5);
        assertEquals(5, encryptedDek.getGeneration());
    }

    @Test
    @DisplayName("a row built from a below-1 generation names the generation it will be read back as")
    void dekIdRoundTripsForABelowFirstGenerationRow() {
        // The whole point of the normalization. Without it this row would seal under
        // 'tenant-1#g0', generationOf would read that back as 1, and the ciphertext
        // would be opened with a different key than sealed it.
        EncryptedDek encryptedDek = dek(0);

        String dekId = encryptedDek.dekId();

        assertEquals(EncryptedDek.dekId(TENANT, EncryptedDek.FIRST_GENERATION), dekId);
        assertEquals(encryptedDek.getGeneration(), EncryptedDek.generationOf(TENANT, dekId));
    }

    @Test
    @DisplayName("dekId round trip — every generation names itself")
    void dekIdRoundTrips() {
        for (int generation = 1; generation <= 3; generation++) {
            EncryptedDek encryptedDek = dek(generation);
            assertEquals(generation, EncryptedDek.generationOf(TENANT, encryptedDek.dekId()));
        }
    }

    @Test
    @DisplayName("generationOf — anything that is not a generation name reads as 1")
    void generationOfFallsBackToFirstGeneration() {
        assertEquals(EncryptedDek.FIRST_GENERATION, EncryptedDek.generationOf(TENANT, null));
        assertEquals(EncryptedDek.FIRST_GENERATION, EncryptedDek.generationOf(TENANT, "  "));
        assertEquals(EncryptedDek.FIRST_GENERATION, EncryptedDek.generationOf(TENANT, TENANT));
        assertEquals(EncryptedDek.FIRST_GENERATION, EncryptedDek.generationOf(TENANT, TENANT + "#gnonsense"));
    }

    @Test
    @DisplayName("legacy constructor — generation 1")
    void legacyConstructorIsFirstGeneration() {
        var encryptedDek = new EncryptedDek("id", TENANT, "encDek", "iv", Instant.now());

        assertEquals(EncryptedDek.FIRST_GENERATION, encryptedDek.getGeneration());
        assertEquals(TENANT + "#g1", encryptedDek.dekId());
    }

    private static EncryptedDek dek(int generation) {
        return new EncryptedDek("id", TENANT, generation, "encDek", "iv", Instant.now());
    }
}
