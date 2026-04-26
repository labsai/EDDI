/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdSerializerTest {

    private final IdSerializer serializer = new IdSerializer();

    // ==================== isValid ====================

    @Test
    void isValid_valid24CharHex() {
        assertTrue(IdSerializer.isValid("507f1f77bcf86cd799439011"));
    }

    @Test
    void isValid_allLowercase() {
        assertTrue(IdSerializer.isValid("abcdef0123456789abcdef01"));
    }

    @Test
    void isValid_allUppercase() {
        assertTrue(IdSerializer.isValid("ABCDEF0123456789ABCDEF01"));
    }

    @Test
    void isValid_mixedCase() {
        assertTrue(IdSerializer.isValid("aAbBcCdDeEfF001122334455"));
    }

    @Test
    void isValid_allZeros() {
        assertTrue(IdSerializer.isValid("000000000000000000000000"));
    }

    @Test
    void isValid_tooShort() {
        assertFalse(IdSerializer.isValid("507f1f77bcf86cd79943901"));
    }

    @Test
    void isValid_tooLong() {
        assertFalse(IdSerializer.isValid("507f1f77bcf86cd7994390111"));
    }

    @Test
    void isValid_invalidChars() {
        assertFalse(IdSerializer.isValid("507f1f77bcf86cd79943901g"));
    }

    @Test
    void isValid_empty() {
        assertFalse(IdSerializer.isValid(""));
    }

    @Test
    void isValid_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> IdSerializer.isValid(null));
    }

    // ==================== serialize (non-BSON, JsonGenerator) ====================

    @Test
    void serialize_nonBson_writesString() throws Exception {
        var generator = mock(JsonGenerator.class);
        var provider = mock(SerializerProvider.class);

        serializer.serialize("507f1f77bcf86cd799439011", generator, provider);
        verify(generator).writeString("507f1f77bcf86cd799439011");
    }

    @Test
    void serialize_nonBson_plainString() throws Exception {
        var generator = mock(JsonGenerator.class);
        var provider = mock(SerializerProvider.class);

        serializer.serialize("not-an-objectid", generator, provider);
        verify(generator).writeString("not-an-objectid");
    }
}
