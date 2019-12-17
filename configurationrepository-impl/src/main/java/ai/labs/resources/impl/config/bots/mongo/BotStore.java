package ai.labs.resources.impl.config.bots.mongo;

import ai.labs.models.DocumentDescriptor;
import ai.labs.persistence.IResourceStore;
import ai.labs.persistence.mongo.HistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.resources.impl.descriptor.mongo.DocumentDescriptorStore;
import ai.labs.resources.impl.utilities.ResourceUtilities;
import ai.labs.resources.rest.config.bots.IBotStore;
import ai.labs.resources.rest.config.bots.model.BotConfiguration;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.utilities.RuntimeUtilities;
import ai.labs.utilities.URIUtilities;
import com.mongodb.client.MongoDatabase;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;

import javax.inject.Inject;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class BotStore implements IBotStore {
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final BotHistorizedResourceStore botResourceStore;

    @Inject
    public BotStore(MongoDatabase database, IDocumentBuilder documentBuilder, DocumentDescriptorStore documentDescriptorStore) {
        this.documentDescriptorStore = documentDescriptorStore;
        RuntimeUtilities.checkNotNull(database, "database");
        final String collectionName = "bots";
        BotMongoResourceStorage resourceStorage =
                new BotMongoResourceStorage(database, collectionName, documentBuilder, BotConfiguration.class);
        this.botResourceStore = new BotHistorizedResourceStore(resourceStorage);
    }

    @Override
    public BotConfiguration readIncludingDeleted(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return botResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceId create(BotConfiguration botConfiguration) throws ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(botConfiguration.getPackages(), "packages");
        return botResourceStore.create(botConfiguration);
    }

    @Override
    public BotConfiguration read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return botResourceStore.read(id, version);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, BotConfiguration botConfiguration) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(botConfiguration.getPackages(), "packages");
        return botResourceStore.update(id, version, botConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version) throws ResourceModifiedException, ResourceNotFoundException {
        botResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        botResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return botResourceStore.getCurrentResourceId(id);
    }

    public List<DocumentDescriptor> getBotDescriptorsContainingPackage(String packageId, Integer packageVersion, boolean includePreviousVersions)
            throws ResourceNotFoundException, ResourceStoreException {

        List<DocumentDescriptor> ret = new LinkedList<>();
        do {
            List<IResourceId> botIdsContainingPackageUri =
                    botResourceStore.getBotIdsContainingPackage(packageId, packageVersion);

            for (IResourceId botId : botIdsContainingPackageUri) {

                if (botId.getVersion() < getCurrentResourceId(botId.getId()).getVersion()) {
                    continue;
                }

                boolean alreadyContainsResource = ret.stream().anyMatch(
                        descriptor ->
                                URIUtilities.extractResourceId(descriptor.getResource()).getId().equals(botId.getId()));

                if (alreadyContainsResource) {
                    continue;
                }

                try {
                    var botDescriptor = documentDescriptorStore.readDescriptor(botId.getId(), botId.getVersion());
                    ret.add(botDescriptor);
                } catch (ResourceNotFoundException e) {
                    //skip, as this resource is not available anymore due to deletion
                }
            }

            packageVersion--;
        } while (includePreviousVersions && packageVersion >= 1);

        return ret;
    }

    private class BotMongoResourceStorage extends MongoResourceStorage<BotConfiguration> {
        private static final String packageResourceURI = "eddi://ai.labs.package/packagestore/packages/";
        private static final String versionQueryParam = "?version=";

        BotMongoResourceStorage(MongoDatabase database, String collectionName, IDocumentBuilder documentBuilder, Class<BotConfiguration> botConfigurationClass) {
            super(database, collectionName, documentBuilder, botConfigurationClass);
        }

        List<IResourceStore.IResourceId> getBotIdsContainingPackageUri(String packageId, Integer packageVersion)
                throws ResourceNotFoundException {

            String searchedForPackageUri = String.join("",
                    packageResourceURI, packageId, versionQueryParam, String.valueOf(packageVersion));
            Document filter = new Document("packages",
                    new Document("$in", Collections.singletonList(searchedForPackageUri)));

            return ResourceUtilities.getAllConfigsContainingResources(filter,
                    currentCollection, historyCollection, documentDescriptorStore);
        }
    }

    private class BotHistorizedResourceStore extends HistorizedResourceStore<BotConfiguration> {
        private final BotMongoResourceStorage resourceStorage;

        BotHistorizedResourceStore(BotMongoResourceStorage resourceStorage) {
            super(resourceStorage);
            this.resourceStorage = resourceStorage;
        }

        List<IResourceId> getBotIdsContainingPackage(String packageId, Integer packageVersion)
                throws ResourceNotFoundException {
            return resourceStorage.getBotIdsContainingPackageUri(packageId, packageVersion);
        }
    }
}
