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
package ai.labs.eddi.datastore.mongo.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.RawBsonDocument;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;

public class JacksonCodec<T> implements Codec<T> {
    private final ObjectMapper bsonObjectMapper;
    private final Codec<RawBsonDocument> rawBsonDocumentCodec;
    private final Class<T> type;

    public JacksonCodec(ObjectMapper bsonObjectMapper,
                        CodecRegistry codecRegistry,
                        Class<T> type) {
        this.bsonObjectMapper = bsonObjectMapper;
        this.rawBsonDocumentCodec = codecRegistry.get(RawBsonDocument.class);
        this.type = type;
    }

    @Override
    public T decode(BsonReader reader, DecoderContext decoderContext) {
        try {
            RawBsonDocument document = rawBsonDocumentCodec.decode(reader, decoderContext);
            return bsonObjectMapper.readValue(document.getByteBuffer().array(), type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void encode(BsonWriter writer, Object value, EncoderContext encoderContext) {
        try {
            byte[] data = bsonObjectMapper.writeValueAsBytes(value);
            rawBsonDocumentCodec.encode(writer, new RawBsonDocument(data, 0, data.length), encoderContext);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Class<T> getEncoderClass() {
        return this.type;
    }
}
