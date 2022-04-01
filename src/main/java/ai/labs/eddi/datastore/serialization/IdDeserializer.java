/*
 * Copyright 2015 Yann Le Moigne
 * https://github.com/ylemoigne/mongo-jackson-codec
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.labs.eddi.datastore.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import de.undercouch.bson4jackson.BsonConstants;
import de.undercouch.bson4jackson.BsonParser;
import de.undercouch.bson4jackson.types.ObjectId;

import java.io.IOException;

public class IdDeserializer extends JsonDeserializer<String> {
    @Override
    public String deserialize(JsonParser jsonParser, DeserializationContext ctxt)
            throws IOException {
        if (!(jsonParser instanceof BsonParser)) {
            return jsonParser.getValueAsString();
        }
        return deserialize((BsonParser) jsonParser, ctxt);
    }

    public String deserialize(BsonParser bsonParser, DeserializationContext ctxt) throws IOException {
        if (bsonParser.getCurrentToken() != JsonToken.VALUE_EMBEDDED_OBJECT ||
                bsonParser.getCurrentBsonType() != BsonConstants.TYPE_OBJECTID) {
            ctxt.handleUnexpectedToken(ObjectId.class, bsonParser.enable(null));
        }

        ObjectId parsedObjectId = (ObjectId) bsonParser.getEmbeddedObject();
        int timestamp = parsedObjectId.getTime();
        int machineAndProcessIdentifier = parsedObjectId.getMachine();
        int counter = parsedObjectId.getInc();

        byte[] bytes = new byte[12];
        bytes[0] = int3(timestamp);
        bytes[1] = int2(timestamp);
        bytes[2] = int1(timestamp);
        bytes[3] = int0(timestamp);
        bytes[4] = int3(machineAndProcessIdentifier);
        bytes[5] = int2(machineAndProcessIdentifier);
        bytes[6] = int1(machineAndProcessIdentifier);
        bytes[7] = int0(machineAndProcessIdentifier);
        bytes[8] = int3(counter);
        bytes[9] = int2(counter);
        bytes[10] = int1(counter);
        bytes[11] = int0(counter);

        StringBuilder buf = new StringBuilder(24);

        for (final byte b : bytes) {
            buf.append(String.format("%02x", b & 0xff));
        }

        return buf.toString();
    }

    private static byte int3(final int x) {
        return (byte) (x >> 24);
    }

    private static byte int2(final int x) {
        return (byte) (x >> 16);
    }

    private static byte int1(final int x) {
        return (byte) (x >> 8);
    }

    private static byte int0(final int x) {
        return (byte) (x);
    }
}
