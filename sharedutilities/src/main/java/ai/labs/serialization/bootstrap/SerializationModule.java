package ai.labs.serialization.bootstrap;

import ai.labs.schema.IJsonSchemaCreator;
import ai.labs.schema.JsonSchemaCreator;
import ai.labs.serialization.DocumentBuilder;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.serialization.JsonSerialization;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;

/**
 * @author ginccc
 */
public class SerializationModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(IJsonSerialization.class).to(JsonSerialization.class).in(Scopes.SINGLETON);
        bind(IJsonSchemaCreator.class).to(JsonSchemaCreator.class).in(Scopes.SINGLETON);
        bind(IDocumentBuilder.class).to(DocumentBuilder.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    public ObjectMapper provideObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return objectMapper;
    }
}
