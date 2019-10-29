package ai.labs.persistence.bootstrap;

import ai.labs.persistence.mongo.codec.JacksonProvider;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.utilities.RuntimeUtilities;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Provides;
import com.mongodb.*;
import com.mongodb.client.MongoDatabase;
import de.undercouch.bson4jackson.BsonFactory;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.codecs.*;
import org.bson.codecs.configuration.CodecRegistry;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.List;

import static ai.labs.SerializationUtilities.configureObjectMapper;
import static org.bson.codecs.configuration.CodecRegistries.*;

/**
 * @author ginccc
 */
public class PersistenceModule extends AbstractBaseModule {
    private final InputStream configFile;

    public PersistenceModule(InputStream configFile) {
        this.configFile = configFile;
    }

    @Override
    protected void configure() {
        registerConfigFiles(this.configFile);
    }

    @Provides
    @Singleton
    public MongoDatabase provideMongoDB(@Named("mongodb.hosts") String hosts,
                                        @Named("mongodb.port") Integer port,
                                        @Named("mongodb.database") String database,
                                        @Named("mongodb.source") String source,
                                        @Named("mongodb.username") String username,
                                        @Named("mongodb.password") String password,
                                        @Named("mongodb.connectionsPerHost") Integer connectionsPerHost,
                                        @Named("mongodb.connectTimeout") Integer connectTimeout,
                                        @Named("mongodb.heartbeatConnectTimeout") Integer heartbeatConnectTimeout,
                                        @Named("mongodb.heartbeatFrequency") Integer heartbeatFrequency,
                                        @Named("mongodb.heartbeatSocketTimeout") Integer heartbeatSocketTimeout,
                                        @Named("mongodb.localThreshold") Integer localThreshold,
                                        @Named("mongodb.maxConnectionIdleTime") Integer maxConnectionIdleTime,
                                        @Named("mongodb.maxConnectionLifeTime") Integer maxConnectionLifeTime,
                                        @Named("mongodb.maxWaitTime") Integer maxWaitTime,
                                        @Named("mongodb.minConnectionsPerHost") Integer minConnectionsPerHost,
                                        @Named("mongodb.minHeartbeatFrequency") Integer minHeartbeatFrequency,
                                        @Named("mongodb.requiredReplicaSetName") String requiredReplicaSetName,
                                        @Named("mongodb.serverSelectionTimeout") Integer serverSelectionTimeout,
                                        @Named("mongodb.socketTimeout") Integer socketTimeout,
                                        @Named("mongodb.sslEnabled") Boolean sslEnabled,
                                        @Named("mongodb.threadsAllowedToBlockForConnectionMultiplier") Integer threadsAllowedToBlockForConnectionMultiplier,
                                        BsonFactory bsonFactory) {
        try {

            List<ServerAddress> seeds = hostsToServerAddress(hosts, port);

            MongoClient mongoClient;
            MongoClientOptions mongoClientOptions = buildMongoClientOptions(
                    ReadPreference.nearest(), connectionsPerHost, connectTimeout,
                    heartbeatConnectTimeout, heartbeatFrequency, heartbeatSocketTimeout,
                    localThreshold, maxConnectionIdleTime, maxConnectionLifeTime, maxWaitTime,
                    minConnectionsPerHost, minHeartbeatFrequency, requiredReplicaSetName,
                    serverSelectionTimeout, socketTimeout, sslEnabled,
                    threadsAllowedToBlockForConnectionMultiplier, bsonFactory);
            if ("".equals(username) || "".equals(password)) {
                mongoClient = new MongoClient(seeds, mongoClientOptions);
            } else {
                MongoCredential credential = MongoCredential.createCredential(username, source, password.toCharArray());
                mongoClient = new MongoClient(seeds, credential, mongoClientOptions);
            }

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
                                                       Integer threadsAllowedToBlockForConnectionMultiplier,
                                                       BsonFactory bsonFactory) {
        MongoClientOptions.Builder builder = MongoClientOptions.builder();
        CodecRegistry codecRegistry = fromRegistries(
                MongoClient.getDefaultCodecRegistry(),
                fromCodecs(new URIStringCodec(), new RawBsonDocumentCodec()),
                fromProviders(
                        new ValueCodecProvider(), new BsonValueCodecProvider(),
                        new DocumentCodecProvider(), new IterableCodecProvider(), new MapCodecProvider(),
                        new JacksonProvider(configureObjectMapper(new ObjectMapper(bsonFactory)))
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
        if (!RuntimeUtilities.isNullOrEmpty(requiredReplicaSetName)) {
            builder.requiredReplicaSetName(requiredReplicaSetName);
        }
        builder.serverSelectionTimeout(serverSelectionTimeout);
        builder.socketTimeout(socketTimeout);
        builder.sslEnabled(sslEnabled);
        builder.threadsAllowedToBlockForConnectionMultiplier(threadsAllowedToBlockForConnectionMultiplier);
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
                throw new BsonInvalidOperationException(
                        String.format("Cannot create URI from string '%s'", uriString));

            }
        }

    }
}
