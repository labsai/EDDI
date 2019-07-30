package ai.labs.channels.config;

import ai.labs.channels.config.model.ChannelDefinition;
import ai.labs.persistence.IResourceStore;
import ai.labs.serialization.IDocumentBuilder;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

import javax.inject.Inject;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import static ai.labs.utilities.RuntimeUtilities.checkNotEmpty;
import static ai.labs.utilities.RuntimeUtilities.checkNotNull;

public class ChannelDefinitionStore implements IChannelDefinitionStore {
    private static final String COLLECTION_CHANNELS = "channels";
    private static final String NAME_FIELD = "name";
    private final MongoCollection<Document> collection;
    private final IDocumentBuilder documentBuilder;
    private ChannelDefinitionResourceStore channelDefinitionResourceStore;

    @Inject
    public ChannelDefinitionStore(MongoDatabase database,
                                  IDocumentBuilder documentBuilder) {
        checkNotNull(database, "database");
        this.collection = database.getCollection(COLLECTION_CHANNELS);
        this.documentBuilder = documentBuilder;
        this.channelDefinitionResourceStore = new ChannelDefinitionResourceStore();
        collection.createIndex(Indexes.ascending(NAME_FIELD));
    }

    @Override
    public List<ChannelDefinition> readAllChannelDefinitions() throws IResourceStore.ResourceStoreException {
        return channelDefinitionResourceStore.readAllChannelDefinition();
    }

    @Override
    public void createChannelDefinition(ChannelDefinition channelDefinition)
            throws IResourceStore.ResourceAlreadyExistsException, IResourceStore.ResourceStoreException {

        checkNotNull(channelDefinition, "channelDefinition");
        checkNotEmpty(channelDefinition.getName(), "channelDefinition.name");
        checkNotEmpty(channelDefinition.getName(), "channelDefinition.type");

        channelDefinitionResourceStore.createChannelDefinition(channelDefinition);
    }

    @Override
    public void deleteChannelDefinition(String botUserId) {
        checkNotNull(botUserId, NAME_FIELD);
        channelDefinitionResourceStore.deleteChannelDefinition(botUserId);
    }

    @Override
    public ChannelDefinition readChannelDefinition(String name)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {

        return channelDefinitionResourceStore.readChannelDefinition(name);
    }

    private class ChannelDefinitionResourceStore {
        static final String NAME_FIELD = "name";

        void createChannelDefinition(ChannelDefinition channelDefinition)
                throws IResourceStore.ResourceStoreException, IResourceStore.ResourceAlreadyExistsException {

            try {
                var alreadyExisting = findChannelByName(channelDefinition.getName());
                if (alreadyExisting != null) {
                    String message = "ChannelDefinition with name {} is already defined in the ChannelDefinition: {}";
                    message = String.format(message, channelDefinition.getName(), alreadyExisting);
                    throw new IResourceStore.ResourceAlreadyExistsException(message);
                }

                collection.insertOne(documentBuilder.toDocument(channelDefinition));
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }

        List<ChannelDefinition> readAllChannelDefinition()
                throws IResourceStore.ResourceStoreException {

            try {
                List<ChannelDefinition> ret = new LinkedList<>();

                var documents = collection.find();
                for (Document document : documents) {
                    ret.add(documentBuilder.build(document, ChannelDefinition.class));
                }

                return ret;
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }

        private Document findChannelByName(String name) {
            return collection.find(new Document(NAME_FIELD, name)).first();
        }

        private void deleteChannelDefinition(String name) {
            collection.deleteOne(new Document(NAME_FIELD, name));
        }

        private ChannelDefinition readChannelDefinition(String name)
                throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {

            Document channelByName = findChannelByName(name);
            if (channelByName == null) {
                String message = "ChannelDefinition with the name %s has not been found.";
                message = String.format(message, name);
                throw new IResourceStore.ResourceNotFoundException(message);
            }
            try {
                return documentBuilder.build(channelByName, ChannelDefinition.class);
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }
    }
}
