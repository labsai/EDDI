/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;

/**
 * @author ginccc
 */
@ApplicationScoped
public final class JsonSerialization implements IJsonSerialization {
    private final ObjectMapper objectMapper;
    private final ObjectWriter writer;
    private final ObjectReader untypedReader;

    @Inject
    public JsonSerialization(@PersistenceMapper ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.writer = objectMapper.writer();
        this.untypedReader = objectMapper.readerFor(Object.class);
    }

    @Override
    public String serialize(Object model) throws IOException {
        return writer.writeValueAsString(model);
    }

    @Override
    public <T> T deserialize(String json) throws IOException {
        return untypedReader.readValue(json);
    }

    @Override
    public <T> T deserialize(String json, Class<T> type) throws IOException {
        return objectMapper.readerFor(type).readValue(json);
    }
}
