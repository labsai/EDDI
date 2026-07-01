package ai.labs.eddi.datastore.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import de.undercouch.bson4jackson.BsonConstants;
import de.undercouch.bson4jackson.BsonParser;
import de.undercouch.bson4jackson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdDeserializerTest {

    private IdDeserializer deserializer;

    @Mock
    private DeserializationContext ctxt;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        deserializer = new IdDeserializer();
    }

    @Test
    @DisplayName("deserialize — non-BsonParser returns value as string")
    void deserializeNonBsonParser() throws Exception {
        JsonParser parser = mock(JsonParser.class);
        when(parser.getValueAsString()).thenReturn("myStringId");

        String result = deserializer.deserialize(parser, ctxt);
        assertEquals("myStringId", result);
    }

    @Test
    @DisplayName("deserialize — BsonParser with ObjectId returns hex string")
    void deserializeBsonParser() throws Exception {
        BsonParser bsonParser = mock(BsonParser.class);
        when(bsonParser.getCurrentToken()).thenReturn(JsonToken.VALUE_EMBEDDED_OBJECT);
        when(bsonParser.getCurrentBsonType()).thenReturn(BsonConstants.TYPE_OBJECTID);

        ObjectId objectId = new ObjectId(0x5f3d2e1a, 0x0b1c2d3e, 0x4f5a6b7c);
        when(bsonParser.getEmbeddedObject()).thenReturn(objectId);

        String result = deserializer.deserialize(bsonParser, ctxt);

        assertNotNull(result);
        assertEquals(24, result.length());
        assertEquals("5f3d2e1a0b1c2d3e4f5a6b7c", result);
    }

    @Test
    @DisplayName("deserialize — BsonParser with zero ObjectId returns all zeros")
    void deserializeBsonParserZero() throws Exception {
        BsonParser bsonParser = mock(BsonParser.class);
        when(bsonParser.getCurrentToken()).thenReturn(JsonToken.VALUE_EMBEDDED_OBJECT);
        when(bsonParser.getCurrentBsonType()).thenReturn(BsonConstants.TYPE_OBJECTID);

        ObjectId objectId = new ObjectId(0, 0, 0);
        when(bsonParser.getEmbeddedObject()).thenReturn(objectId);

        String result = deserializer.deserialize(bsonParser, ctxt);
        assertEquals("000000000000000000000000", result);
    }

    @Test
    @DisplayName("deserialize — BsonParser with max values")
    void deserializeBsonParserMaxValues() throws Exception {
        BsonParser bsonParser = mock(BsonParser.class);
        when(bsonParser.getCurrentToken()).thenReturn(JsonToken.VALUE_EMBEDDED_OBJECT);
        when(bsonParser.getCurrentBsonType()).thenReturn(BsonConstants.TYPE_OBJECTID);

        ObjectId objectId = new ObjectId(0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF);
        when(bsonParser.getEmbeddedObject()).thenReturn(objectId);

        String result = deserializer.deserialize(bsonParser, ctxt);
        assertEquals("ffffffffffffffffffffffff", result);
    }
}
