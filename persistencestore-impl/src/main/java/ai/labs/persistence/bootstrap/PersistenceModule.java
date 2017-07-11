package ai.labs.persistence.bootstrap;

import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.utilities.RuntimeUtilities;
import com.google.inject.Provides;
import com.mongodb.*;
import com.mongodb.client.MongoDatabase;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

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
                                        @Named("mongodb.socketKeepAlive") Boolean socketKeepAlive,
                                        @Named("mongodb.socketTimeout") Integer socketTimeout,
                                        @Named("mongodb.sslEnabled") Boolean sslEnabled,
                                        @Named("mongodb.threadsAllowedToBlockForConnectionMultiplier") Integer threadsAllowedToBlockForConnectionMultiplier) {
        try {

            List<ServerAddress> seeds = hostsToServerAddress(hosts, port);

            MongoClient mongoClient;
            MongoClientOptions mongoClientOptions = buildMongoClientOptions(
                    WriteConcern.MAJORITY, ReadPreference.nearest(),
                    connectionsPerHost, connectTimeout, heartbeatConnectTimeout,
                    heartbeatFrequency, heartbeatSocketTimeout, localThreshold,
                    maxConnectionIdleTime, maxConnectionLifeTime, maxWaitTime,
                    minConnectionsPerHost, minHeartbeatFrequency, requiredReplicaSetName,
                    serverSelectionTimeout, socketKeepAlive, socketTimeout,
                    sslEnabled, threadsAllowedToBlockForConnectionMultiplier);
            if ("".equals(username) || "".equals(password)) {
                mongoClient = new MongoClient(seeds, mongoClientOptions);
            } else {
                MongoCredential credential = MongoCredential.createCredential(username, source, password.toCharArray());
                mongoClient = new MongoClient(seeds, Collections.singletonList(credential), mongoClientOptions);
            }

            registerMongoClientShutdownHook(mongoClient);

            return mongoClient.getDatabase(database);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e.getLocalizedMessage(), e);
        }
    }

    private MongoClientOptions buildMongoClientOptions(WriteConcern writeConcern, ReadPreference readPreference,
                                                       Integer connectionsPerHost, Integer connectTimeout,
                                                       Integer heartbeatConnectTimeout, Integer heartbeatFrequency,
                                                       Integer heartbeatSocketTimeout, Integer localThreshold,
                                                       Integer maxConnectionIdleTime, Integer maxConnectionLifeTime,
                                                       Integer maxWaitTime, Integer minConnectionsPerHost,
                                                       Integer minHeartbeatFrequency, String requiredReplicaSetName,
                                                       Integer serverSelectionTimeout, Boolean socketKeepAlive,
                                                       Integer socketTimeout, Boolean sslEnabled,
                                                       Integer threadsAllowedToBlockForConnectionMultiplier) {
        MongoClientOptions.Builder builder = MongoClientOptions.builder();
        builder.writeConcern(writeConcern);
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
        builder.socketKeepAlive(socketKeepAlive);
        builder.socketTimeout(socketTimeout);
        builder.sslEnabled(sslEnabled);
        builder.threadsAllowedToBlockForConnectionMultiplier(threadsAllowedToBlockForConnectionMultiplier);
        return builder.build();
    }

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
}
