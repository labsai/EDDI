package io.sls.persistence.impl.bootstrap;

import com.google.inject.Provides;
import com.mongodb.*;
import io.sls.runtime.bootstrap.AbstractBaseModule;

import javax.inject.Named;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by jariscgr on 08.08.2016.
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
    public DB provideMongoDB(@Named("mongodb.hosts") String hosts,
                             @Named("mongodb.port") Integer port,
                             @Named("mongodb.database") String database,
                             @Named("mongodb.username") String username,
                             @Named("mongodb.password") String password,
                             @Named("mongodb.connectionsPerHost") Integer connectionsPerHost) {
        try {
            List<ServerAddress> seeds = hostsToServerAddress(hosts, port);
            MongoClient mongoClient;
            MongoClientOptions mongoClientOptions = buildMongoClientOptions(connectionsPerHost);
            if ("".equals(username) || "".equals(password)) {
                mongoClient = new MongoClient(seeds, mongoClientOptions);
            } else {
                MongoCredential credential = MongoCredential.createCredential(username, database, password.toCharArray());
                mongoClient = new MongoClient(seeds, Collections.singletonList(credential), mongoClientOptions);
                mongoClient.setWriteConcern(WriteConcern.MAJORITY);
                mongoClient.setReadPreference(ReadPreference.nearest());
            }

            registerMongoClientShutdownHook(mongoClient);

            return mongoClient.getDB(database);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e.getLocalizedMessage(), e);
        }
    }

    private MongoClientOptions buildMongoClientOptions(Integer connectionsPerHost) {
        MongoClientOptions.Builder builder = MongoClientOptions.builder();
        builder.connectionsPerHost(connectionsPerHost);
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
