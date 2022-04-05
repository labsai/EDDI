package ai.labs.eddi.datastore.bootstrap;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.Produces;

/**
 * @author ginccc
 */
@ApplicationScoped
public class PersistenceModule {
    @Produces
    @ApplicationScoped
    public MongoDatabase provideMongoDB(MongoClient mongoClient,
                                        @ConfigProperty(name = "quarkus.mongodb.database") String database) {

        return mongoClient.getDatabase(database);
    }
}
