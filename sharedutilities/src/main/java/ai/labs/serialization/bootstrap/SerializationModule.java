package ai.labs.serialization.bootstrap;

import ai.labs.schema.IJsonSchemaCreator;
import ai.labs.schema.JsonSchemaCreator;
import ai.labs.serialization.DocumentBuilder;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.serialization.JsonSerialization;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import de.undercouch.bson4jackson.BsonFactory;
import de.undercouch.bson4jackson.BsonParser;

import javax.inject.Named;

import static ai.labs.SerializationUtilities.configureObjectMapper;

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
    public ObjectMapper provideObjectMapper(@Named("json.prettyPrint") boolean prettyPrint, JsonFactory jsonFactory) {
        ObjectMapper objectMapper = new ObjectMapper(jsonFactory);

        configureObjectMapper(prettyPrint, objectMapper);
        return objectMapper;
    }

    @Provides
    @Singleton
    public JsonFactory provideJsonFactory() {
        JsonFactory jsonFactory = new JsonFactory();
        jsonFactory.disable(JsonFactory.Feature.CANONICALIZE_FIELD_NAMES);
        return jsonFactory;
    }

    @Provides
    @Singleton
    public BsonFactory provideBsonFactory() {
        BsonFactory bsonFactory = new BsonFactory();
        bsonFactory.enable(BsonParser.Feature.HONOR_DOCUMENT_LENGTH);
        return bsonFactory;
    }

}
