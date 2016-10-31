package io.sls.serialization.bootstrap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import io.sls.serialization.DocumentBuilder;
import io.sls.serialization.IDocumentBuilder;
import io.sls.serialization.IJsonSerialization;
import io.sls.serialization.JsonSerialization;

/**
 * @author ginccc
 */
public class SerializationModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(IJsonSerialization.class).to(JsonSerialization.class).in(Scopes.SINGLETON);
        bind(IDocumentBuilder.class).to(DocumentBuilder.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    public ObjectMapper provideObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return objectMapper;
    }

    @Provides
    @Singleton
    public JacksonJsonProvider jacksonJsonProvider(ObjectMapper mapper) {
        return new JacksonJsonProvider(mapper);
    }
}
