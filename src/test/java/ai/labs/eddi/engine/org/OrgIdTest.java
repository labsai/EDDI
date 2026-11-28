/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"default", "acme", "a1", "0rg", "acme-corp", "a-"})
    void acceptsValidIds(String value) {
        assertEquals(value, OrgId.of(value).value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "Acme", "-acme", "acme_corp", "acme.corp", "acme corp", "äcme", "acme\n", " acme"})
    void refusesInvalidIds(String value) {
        assertThrows(IllegalArgumentException.class, () -> OrgId.of(value));
    }

    @Test
    void refusesNullAndOverlongIds() {
        assertThrows(NullPointerException.class, () -> OrgId.of(null));
        assertEquals(63, OrgId.of("a".repeat(63)).value().length());
        assertThrows(IllegalArgumentException.class, () -> OrgId.of("a".repeat(64)));
    }

    @Test
    void reservedIdsAreNotCreatableButDefaultStillParses() {
        for (String reserved : OrgId.RESERVED) {
            assertFalse(OrgId.isCreatable(reserved), reserved);
        }
        assertTrue(OrgId.of("default").isDefault());
        assertTrue(OrgId.isCreatable("acme"));
        assertFalse(OrgId.isCreatable("Acme"));
        assertFalse(OrgId.isCreatable(null));
    }

    @Test
    void defaultIsAConstantValueObject() {
        assertEquals(OrgId.DEFAULT, OrgId.of("default"));
        assertEquals("default", OrgId.DEFAULT.toString());
        assertFalse(OrgId.of("acme").isDefault());
    }
}
