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


import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import de.undercouch.bson4jackson.BsonGenerator;
import de.undercouch.bson4jackson.types.ObjectId;

import java.io.IOException;

public class IdSerializer extends JsonSerializer<String> {
    @Override
    public void serialize(String t, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
        if (!(jsonGenerator instanceof BsonGenerator)) {
            jsonGenerator.writeString(t);
            return;
        }
        serialize(t, (BsonGenerator) jsonGenerator, serializerProvider);
    }

    public void serialize(String s, BsonGenerator bsonGenerator, SerializerProvider serializerProvider) throws
            IOException {
        if (s == null) {
            serializerProvider.defaultSerializeNull(bsonGenerator);
            return;
        }

        if (!isValid(s)) {
            throw new IllegalArgumentException("invalid hexadecimal representation of an ObjectId: [" + s + "]");
        }

        bsonGenerator.writeObjectId(createObjectIdFromString(s));
    }

    private static ObjectId createObjectIdFromString(String s) {
        int[] parsed = parse(s);

        int time = (parsed[0] << 24) + (parsed[1] << 16) + (parsed[2] << 8) + parsed[3];
        int machine = (parsed[4] << 24) + (parsed[5] << 16) + (parsed[6] << 8) + parsed[7];
        int inc = (parsed[8] << 24) + (parsed[9] << 16) + (parsed[10] << 8) + parsed[11];

        return new ObjectId(time, machine, inc);
    }

    private static int[] parse(String s) {
        int[] b = new int[12];
        for (int i = 0; i < b.length; i++) {
            b[i] = Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    public static boolean isValid(final String hexString) {
        if (hexString == null) {
            throw new IllegalArgumentException();
        }

        int len = hexString.length();
        if (len != 24) {
            return false;
        }

        for (int i = 0; i < len; i++) {
            char c = hexString.charAt(i);
            if (c >= '0' && c <= '9') {
                continue;
            }
            if (c >= 'a' && c <= 'f') {
                continue;
            }
            if (c >= 'A' && c <= 'F') {
                continue;
            }

            return false;
        }

        return true;
    }
}
