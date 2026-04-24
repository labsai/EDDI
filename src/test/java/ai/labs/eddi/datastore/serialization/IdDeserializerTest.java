/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdDeserializerTest {

    private final IdDeserializer deserializer = new IdDeserializer();

    @Test
    void deserialize_nonBson_returnsString() throws Exception {
        var parser = mock(JsonParser.class);
        var ctx = mock(DeserializationContext.class);
        when(parser.getValueAsString()).thenReturn("507f1f77bcf86cd799439011");

        var result = deserializer.deserialize(parser, ctx);
        assertEquals("507f1f77bcf86cd799439011", result);
    }

    @Test
    void deserialize_nonBson_plainString() throws Exception {
        var parser = mock(JsonParser.class);
        var ctx = mock(DeserializationContext.class);
        when(parser.getValueAsString()).thenReturn("my-custom-id");

        var result = deserializer.deserialize(parser, ctx);
        assertEquals("my-custom-id", result);
    }

    @Test
    void deserialize_nonBson_null() throws Exception {
        var parser = mock(JsonParser.class);
        var ctx = mock(DeserializationContext.class);
        when(parser.getValueAsString()).thenReturn(null);

        var result = deserializer.deserialize(parser, ctx);
        assertNull(result);
    }
}
