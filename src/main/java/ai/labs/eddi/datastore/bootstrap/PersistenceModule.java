/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.bootstrap;

import ai.labs.eddi.datastore.mongo.codec.JacksonProvider;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import de.undercouch.bson4jackson.BsonFactory;
import de.undercouch.bson4jackson.BsonParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Produces;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.codecs.*;
import org.bson.codecs.configuration.CodecRegistry;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.URISyntaxException;
import io.quarkus.arc.DefaultBean;

import static org.bson.codecs.configuration.CodecRegistries.*;

/**
 * MongoDB persistence module. Produces the {@link MongoDatabase} CDI bean.
 * <p>
 * Annotated {@code @DefaultBean} so it yields to the PostgreSQL datastore layer
 * when the {@code postgres} profile is active. CDI lazy initialization ensures
 * {@code MongoDatabase} is never created if no active bean injects it.
 *
 * @author ginccc
 */
@ApplicationScoped
@DefaultBean
public class PersistenceModule {
    @Produces
    @ApplicationScoped
    @DefaultBean
    public MongoDatabase provideMongoDB(@ConfigProperty(name = "mongodb.connectionString") String connectionString,
                                        @ConfigProperty(name = "mongodb.database") String database) {
        BsonFactory bsonFactory = new BsonFactory();
        bsonFactory.enable(BsonParser.Feature.HONOR_DOCUMENT_LENGTH);

        MongoClient client = MongoClients.create(buildMongoClientOptions(ReadPreference.nearest(), connectionString, bsonFactory));
        registerMongoClientShutdownHook(client);

        return client.getDatabase(database);
    }

    private MongoClientSettings buildMongoClientOptions(ReadPreference readPreference, String connectionString, BsonFactory bsonFactory) {

        var objectMapper = new ObjectMapper(bsonFactory);
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new SerializationCustomizer(false).customize(objectMapper);
        CodecRegistry codecRegistry = fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                fromCodecs(new URIStringCodec(), new RawBsonDocumentCodec()), fromProviders(new ValueCodecProvider(), new BsonValueCodecProvider(),
                        new DocumentCodecProvider(), new IterableCodecProvider(), new MapCodecProvider(), new JacksonProvider(objectMapper)));

        return MongoClientSettings.builder().applyConnectionString(new ConnectionString(connectionString)).codecRegistry(codecRegistry)
                .writeConcern(WriteConcern.MAJORITY).readPreference(readPreference).build();
    }

    private void registerMongoClientShutdownHook(final MongoClient mongoClient) {
        Runtime.getRuntime().addShutdownHook(new Thread("ShutdownHook_MongoClient") {
            @Override
            public void run() {
                try {
                    mongoClient.close();
                } catch (Throwable e) {
                    String message = "MongoClient did not stop as expected.";
                    System.out.println(message);
                }
            }
        });
    }

    public static class URIStringCodec implements Codec<URI> {

        @Override
        public Class<URI> getEncoderClass() {
            return URI.class;
        }

        @Override
        public void encode(BsonWriter writer, URI value, EncoderContext encoderContext) {
            writer.writeString(value.toString());
        }

        @Override
        public URI decode(BsonReader reader, DecoderContext decoderContext) {
            String uriString = reader.readString();
            try {
                return new URI(uriString);
            } catch (URISyntaxException e) {
                throw new BsonInvalidOperationException(String.format("Cannot create URI from string '%s'", uriString));

            }
        }
    }
}
