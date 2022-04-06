package ai.labs.eddi.datastore.bootstrap;

import ai.labs.eddi.datastore.mongo.codec.JacksonProvider;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoClientOptions;
import com.mongodb.ReadPreference;
import com.mongodb.ServerAddress;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoDatabase;
import de.undercouch.bson4jackson.BsonFactory;
import de.undercouch.bson4jackson.BsonParser;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.codecs.*;
import org.bson.codecs.configuration.CodecRegistry;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.Produces;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.List;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static org.bson.codecs.configuration.CodecRegistries.*;

/**
 * @author ginccc
 */
@ApplicationScoped
public class PersistenceModule {
    @Produces
    @ApplicationScoped
    public MongoDatabase provideMongoDB(@ConfigProperty(name = "mongodb.hosts") String hosts,
                                        @ConfigProperty(name = "mongodb.port") Integer port,
                                        @ConfigProperty(name = "mongodb.database") String database,
                                        @ConfigProperty(name = "mongodb.source") String source,
                                        /*@ConfigProperty(name = "mongodb.username") String username,
                                        @ConfigProperty(name = "mongodb.password") String password,*/
                                        @ConfigProperty(name = "mongodb.connectionsPerHost") Integer connectionsPerHost,
                                        @ConfigProperty(name = "mongodb.connectTimeout") Integer connectTimeout,
                                        @ConfigProperty(name = "mongodb.heartbeatConnectTimeout") Integer heartbeatConnectTimeout,
                                        @ConfigProperty(name = "mongodb.heartbeatFrequency") Integer heartbeatFrequency,
                                        @ConfigProperty(name = "mongodb.heartbeatSocketTimeout") Integer heartbeatSocketTimeout,
                                        @ConfigProperty(name = "mongodb.localThreshold") Integer localThreshold,
                                        @ConfigProperty(name = "mongodb.maxConnectionIdleTime") Integer maxConnectionIdleTime,
                                        @ConfigProperty(name = "mongodb.maxConnectionLifeTime") Integer maxConnectionLifeTime,
                                        @ConfigProperty(name = "mongodb.maxWaitTime") Integer maxWaitTime,
                                        @ConfigProperty(name = "mongodb.minConnectionsPerHost") Integer minConnectionsPerHost,
                                        @ConfigProperty(name = "mongodb.minHeartbeatFrequency") Integer minHeartbeatFrequency,
            /*@ConfigProperty(name = "mongodb.requiredReplicaSetName") String requiredReplicaSetName,*/
                                        @ConfigProperty(name = "mongodb.serverSelectionTimeout") Integer serverSelectionTimeout,
                                        @ConfigProperty(name = "mongodb.socketTimeout") Integer socketTimeout,
                                        @ConfigProperty(name = "mongodb.sslEnabled") Boolean sslEnabled) {
        try {

            BsonFactory bsonFactory = new BsonFactory();
            bsonFactory.enable(BsonParser.Feature.HONOR_DOCUMENT_LENGTH);

            List<ServerAddress> seeds = hostsToServerAddress(hosts, port);

            com.mongodb.MongoClient mongoClient;
            MongoClientOptions mongoClientOptions = buildMongoClientOptions(
                    ReadPreference.nearest(), connectionsPerHost, connectTimeout,
                    heartbeatConnectTimeout, heartbeatFrequency, heartbeatSocketTimeout,
                    localThreshold, maxConnectionIdleTime, maxConnectionLifeTime, maxWaitTime,
                    minConnectionsPerHost, minHeartbeatFrequency, null,
                    serverSelectionTimeout, socketTimeout, sslEnabled, bsonFactory);
            //if ("".equals(username) || "".equals(password)) {
            mongoClient = new com.mongodb.MongoClient(seeds, mongoClientOptions);
            /*} else {
                MongoCredential credential = MongoCredential.createCredential(username, source, password.toCharArray());
                mongoClient = new com.mongodb.MongoClient(seeds, credential, mongoClientOptions);
            }*/

            registerMongoClientShutdownHook(mongoClient);

            return mongoClient.getDatabase(database);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e.getLocalizedMessage(), e);
        }
    }

    private MongoClientOptions buildMongoClientOptions(ReadPreference readPreference,
                                                       Integer connectionsPerHost, Integer connectTimeout,
                                                       Integer heartbeatConnectTimeout, Integer heartbeatFrequency,
                                                       Integer heartbeatSocketTimeout, Integer localThreshold,
                                                       Integer maxConnectionIdleTime, Integer maxConnectionLifeTime,
                                                       Integer maxWaitTime, Integer minConnectionsPerHost,
                                                       Integer minHeartbeatFrequency, String requiredReplicaSetName,
                                                       Integer serverSelectionTimeout, Integer socketTimeout,
                                                       Boolean sslEnabled,
                                                       BsonFactory bsonFactory) {
        MongoClientOptions.Builder builder = MongoClientOptions.builder();
        var objectMapper = new ObjectMapper(bsonFactory);
        new SerializationCustomizer(false).customize(objectMapper);
        CodecRegistry codecRegistry = fromRegistries(
                com.mongodb.MongoClient.getDefaultCodecRegistry(),
                fromCodecs(new URIStringCodec(), new RawBsonDocumentCodec()),
                fromProviders(
                        new ValueCodecProvider(), new BsonValueCodecProvider(),
                        new DocumentCodecProvider(), new IterableCodecProvider(), new MapCodecProvider(),
                        new JacksonProvider(objectMapper)
                )
        );
        builder.codecRegistry(codecRegistry);
        builder.writeConcern(WriteConcern.MAJORITY);
        builder.readPreference(readPreference);
        builder.connectionsPerHost(connectionsPerHost);
        builder.connectTimeout(connectTimeout);
        builder.heartbeatConnectTimeout(heartbeatConnectTimeout);
        builder.heartbeatFrequency(heartbeatFrequency);
        builder.heartbeatSocketTimeout(heartbeatSocketTimeout);
        builder.localThreshold(localThreshold);
        if (maxConnectionIdleTime >= 0) {
            builder.maxConnectionIdleTime(maxConnectionIdleTime);
        }
        if (maxConnectionLifeTime >= 0) {
            builder.maxConnectionLifeTime(maxConnectionLifeTime);
        }
        builder.maxWaitTime(maxWaitTime);
        if (minConnectionsPerHost >= 0) {
            builder.minConnectionsPerHost(minConnectionsPerHost);
        }
        builder.minHeartbeatFrequency(minHeartbeatFrequency);
        if (!isNullOrEmpty(requiredReplicaSetName)) {
            builder.requiredReplicaSetName(requiredReplicaSetName);
        }
        builder.serverSelectionTimeout(serverSelectionTimeout);
        builder.socketTimeout(socketTimeout);
        builder.sslEnabled(sslEnabled);
        return builder.build();
    }

    @SuppressWarnings("RedundantThrows")
    private static List<ServerAddress> hostsToServerAddress(String hosts, Integer port) throws UnknownHostException {
        List<ServerAddress> ret = new LinkedList<>();

        for (String host : hosts.split(",")) {
            ret.add(new ServerAddress(host.trim(), port));
        }

        return ret;
    }

    private void registerMongoClientShutdownHook(final com.mongodb.MongoClient mongoClient) {
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
