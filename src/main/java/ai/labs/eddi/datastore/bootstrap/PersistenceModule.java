/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.bootstrap;

import ai.labs.eddi.datastore.mongo.MongoDriverInfoFactory;
import ai.labs.eddi.datastore.mongo.codec.JacksonProvider;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoDriverInformation;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import de.undercouch.bson4jackson.BsonFactory;
import de.undercouch.bson4jackson.BsonParser;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Produces;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.codecs.*;
import org.bson.codecs.configuration.CodecRegistry;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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

    private static final MongoDriverInformation DRIVER_INFO = MongoDriverInfoFactory.build();
    private static final Logger LOGGER = Logger.getLogger(PersistenceModule.class);

    private volatile MongoClient mongoClient;

    @Produces
    @ApplicationScoped
    @DefaultBean
    public MongoDatabase provideMongoDB(@ConfigProperty(name = "mongodb.connectionString") String connectionString,
                                        @ConfigProperty(name = "mongodb.database") String database) {
        BsonFactory bsonFactory = new BsonFactory();
        bsonFactory.enable(BsonParser.Feature.HONOR_DOCUMENT_LENGTH);

        MongoClient client = MongoClients.create(buildMongoClientOptions(ReadPreference.nearest(), connectionString, bsonFactory), DRIVER_INFO);
        this.mongoClient = client;

        return client.getDatabase(database);
    }

    /**
     * Closes the client when the CDI container shuts down — AFTER every
     * {@code ShutdownEvent} observer has returned, so the graceful drain
     * ({@code GracefulShutdownService}) still has a database to finish its turns
     * against.
     * <p>
     * It used to be a JVM shutdown hook. JVM hooks run concurrently with Quarkus'
     * own, so SIGTERM closed the client at the very moment the drain began: every
     * turn still running failed with "state should be: open", the drain waited out
     * its whole timeout for turns that could no longer finish, and a rolling update
     * turned in-flight requests into 500s.
     */
    @PreDestroy
    void closeMongoClient() {
        MongoClient client = this.mongoClient;
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (RuntimeException e) {
            LOGGER.warn("MongoClient did not stop as expected", e);
        }
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
