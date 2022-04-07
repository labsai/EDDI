package ai.labs.eddi.configs.properties.mongo;

import ai.labs.eddi.configs.properties.IPropertiesStore;
import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

/**
 * @author ginccc
 */
@ApplicationScoped
public class PropertiesStore implements IPropertiesStore {
    private static final String COLLECTION_PROPERTIES = "properties";
    private static final String USER_ID = "userId";
    private final MongoCollection<Document> collection;
    private final PropertiesResourceStore propertiesStore;

    @Inject
    public PropertiesStore(MongoDatabase database) {
        RuntimeUtilities.checkNotNull(database, "database");
        this.collection = database.getCollection(COLLECTION_PROPERTIES);
        this.propertiesStore = new PropertiesResourceStore();
        collection.createIndex(Indexes.ascending(USER_ID), new IndexOptions().unique(true));
    }

    @Override
    public Properties readProperties(String userId) {
        RuntimeUtilities.checkNotNull(userId, USER_ID);

        return propertiesStore.readProperties(userId);
    }

    @Override
    public void mergeProperties(String userId, Properties properties) {
        RuntimeUtilities.checkNotNull(userId, USER_ID);
        RuntimeUtilities.checkNotNull(properties, "properties");

        if (!properties.isEmpty()) {
            propertiesStore.mergeProperties(userId, properties);
        }
    }

    @Override
    public void deleteProperties(String userId) {
        RuntimeUtilities.checkNotNull(userId, USER_ID);

        propertiesStore.deleteProperties(userId);
    }

    private class PropertiesResourceStore {
        Properties readProperties(String userId) {

            Document filter = new Document();
            filter.put(USER_ID, userId);

            Document document = collection.find(filter).first();
            if (document != null) {
                return new Properties(document);
            }

            return null;
        }

        void mergeProperties(String userId, Properties newProperties) {
            Properties currentProperties = readProperties(userId);
            boolean create = false;
            if (currentProperties == null) {
                currentProperties = new Properties();
                currentProperties.put(USER_ID, userId);
                create = true;
            }

            currentProperties.putAll(newProperties);
            Document propertiesDocument = new Document(currentProperties);

            if (!propertiesDocument.isEmpty()) {
                if (create) {
                    collection.insertOne(propertiesDocument);
                } else {
                    collection.replaceOne(new Document(USER_ID, userId), propertiesDocument);
                }
            }
        }

        void deleteProperties(String userId) {
            collection.deleteOne(new Document(USER_ID, userId));
        }
    }
}
