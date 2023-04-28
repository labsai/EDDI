package ai.labs.eddi.datastore.bootstrap;

import ai.labs.eddi.datastore.mongo.codec.JacksonProvider;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ConnectionString;
import com.mongodb.ServerApi;
import com.mongodb.ServerApiVersion;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoDatabase;
import de.undercouch.bson4jackson.BsonFactory;
import de.undercouch.bson4jackson.BsonParser;
import io.quarkus.mongodb.impl.ReactiveMongoClientImpl;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.codecs.*;
import org.bson.codecs.configuration.CodecRegistry;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Produces;
import java.net.URI;
import java.net.URISyntaxException;

import static org.bson.codecs.configuration.CodecRegistries.*;

/**
 * @author ginccc
 */
@ApplicationScoped
public class PersistenceModule {
    @Produces
    @ApplicationScoped
    public MongoDatabase provideMongoDB(@ConfigProperty(name = "mongodb.connectionString") String connectionString,
                                        @ConfigProperty(name = "mongodb.database") String database) {
            BsonFactory bsonFactory = new BsonFactory();
            bsonFactory.enable(BsonParser.Feature.HONOR_DOCUMENT_LENGTH);


            ReactiveMongoClient client = new ReactiveMongoClientImpl(MongoClients.create(buildMongoClientOptions(ReadPreference.nearest(), connectionString, bsonFactory)));
            registerMongoClientShutdownHook(client);

            return client.getDatabase(database).unwrap();
    }

    private MongoClientSettings buildMongoClientOptions(ReadPreference readPreference,
                                                       String connectionString,
                                                       BsonFactory bsonFactory) {

        var objectMapper = new ObjectMapper(bsonFactory);
        new SerializationCustomizer(false).customize(objectMapper);
        CodecRegistry codecRegistry = fromRegistries(
                MongoClientSettings.getDefaultCodecRegistry(),
                fromCodecs(new URIStringCodec(), new RawBsonDocumentCodec()),
                fromProviders(
                        new ValueCodecProvider(), new BsonValueCodecProvider(),
                        new DocumentCodecProvider(), new IterableCodecProvider(), new MapCodecProvider(),
                        new JacksonProvider(objectMapper)
                )
        );

        return MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connectionString))
                .codecRegistry(codecRegistry)
                .writeConcern(WriteConcern.MAJORITY)
                .readPreference(readPreference).build();
    }

    private void registerMongoClientShutdownHook(final ReactiveMongoClient mongoClient) {
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
                throw new BsonInvalidOperationException(
                        String.format("Cannot create URI from string '%s'", uriString));

            }
        }
    }
}
