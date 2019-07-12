package ai.labs.persistence.bootstrap;

import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.*;
import com.mongodb.client.MongoDatabase;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import java.util.LinkedList;
import java.util.List;

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
                                        @ConfigProperty(name = "mongodb.username") String username,
                                        @ConfigProperty(name = "mongodb.password") String password,
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
                                        @ConfigProperty(name = "mongodb.requiredReplicaSetName") String requiredReplicaSetName,
                                        @ConfigProperty(name = "mongodb.serverSelectionTimeout") Integer serverSelectionTimeout,
                                        @ConfigProperty(name = "mongodb.socketTimeout") Integer socketTimeout,
                                        @ConfigProperty(name = "mongodb.sslEnabled") Boolean sslEnabled,
                                        @ConfigProperty(name = "mongodb.threadsAllowedToBlockForConnectionMultiplier") Integer threadsAllowedToBlockForConnectionMultiplier) {

        List<ServerAddress> seeds = hostsToServerAddress(hosts, port);

        MongoClient mongoClient;
        MongoClientOptions mongoClientOptions = buildMongoClientOptions(
                WriteConcern.MAJORITY, ReadPreference.nearest(),
                connectionsPerHost, connectTimeout, heartbeatConnectTimeout,
                heartbeatFrequency, heartbeatSocketTimeout, localThreshold,
                maxConnectionIdleTime, maxConnectionLifeTime, maxWaitTime,
                minConnectionsPerHost, minHeartbeatFrequency, requiredReplicaSetName,
                serverSelectionTimeout, socketTimeout,
                sslEnabled, threadsAllowedToBlockForConnectionMultiplier);
        if ("".equals(username) || "".equals(password)) {
            mongoClient = new MongoClient(seeds, mongoClientOptions);
        } else {
            MongoCredential credential = MongoCredential.createCredential(username, source, password.toCharArray());
            mongoClient = new MongoClient(seeds, credential, mongoClientOptions);
        }

        registerMongoClientShutdownHook(mongoClient);

        return mongoClient.getDatabase(database);
    }

    private MongoClientOptions buildMongoClientOptions(WriteConcern writeConcern, ReadPreference readPreference,
                                                       Integer connectionsPerHost, Integer connectTimeout,
                                                       Integer heartbeatConnectTimeout, Integer heartbeatFrequency,
                                                       Integer heartbeatSocketTimeout, Integer localThreshold,
                                                       Integer maxConnectionIdleTime, Integer maxConnectionLifeTime,
                                                       Integer maxWaitTime, Integer minConnectionsPerHost,
                                                       Integer minHeartbeatFrequency, String requiredReplicaSetName,
                                                       Integer serverSelectionTimeout, Integer socketTimeout,
                                                       Boolean sslEnabled,
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
        builder.socketTimeout(socketTimeout);
        builder.sslEnabled(sslEnabled);
        builder.threadsAllowedToBlockForConnectionMultiplier(threadsAllowedToBlockForConnectionMultiplier);
        return builder.build();
    }

    private static List<ServerAddress> hostsToServerAddress(String hosts, Integer port) {
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
