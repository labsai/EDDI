package ai.labs.eddi.configs.bots.mongo;

import ai.labs.eddi.configs.bots.IBotStore;
import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.configs.descriptor.mongo.DocumentDescriptorStore;
import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.utilities.ResourceUtilities;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.mongo.HistorizedResourceStore;
import ai.labs.eddi.datastore.mongo.MongoResourceStorage;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.reactivestreams.client.MongoDatabase;
import org.bson.Document;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static ai.labs.eddi.utils.RestUtilities.extractResourceId;

/**
 * @author ginccc
 */

@ApplicationScoped
public class BotStore implements IBotStore {
    public static final String PACKAGES_FIELD = "packages";
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
    public BotConfiguration readIncludingDeleted(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return botResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceStore.IResourceId create(BotConfiguration botConfiguration) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(botConfiguration.getPackages(), PACKAGES_FIELD);
        return botResourceStore.create(botConfiguration);
    }

    @Override
    public BotConfiguration read(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return botResourceStore.read(id, version);
    }

    @Override
    @IResourceStore.ConfigurationUpdate
    public Integer update(String id, Integer version, BotConfiguration botConfiguration) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(botConfiguration.getPackages(), PACKAGES_FIELD);
        return botResourceStore.update(id, version, botConfiguration);
    }

    @Override
    @IResourceStore.ConfigurationUpdate
    public void delete(String id, Integer version) throws IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        botResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        botResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return botResourceStore.getCurrentResourceId(id);
    }

    public List<DocumentDescriptor> getBotDescriptorsContainingPackage(String packageId, Integer packageVersion, boolean includePreviousVersions)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {

        List<DocumentDescriptor> ret = new LinkedList<>();
        do {
            List<IResourceStore.IResourceId> botIdsContainingPackageUri =
                    botResourceStore.getBotIdsContainingPackage(packageId, packageVersion);

            for (IResourceStore.IResourceId botId : botIdsContainingPackageUri) {

                if (botId.getVersion() < getCurrentResourceId(botId.getId()).getVersion()) {
                    continue;
                }

                boolean alreadyContainsResource = ret.stream().anyMatch(
                        descriptor ->
                                extractResourceId(descriptor.getResource()).getId().equals(botId.getId()));

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

        BotMongoResourceStorage(MongoDatabase database, String collectionName,
                                IDocumentBuilder documentBuilder,
                                Class<BotConfiguration> botConfigurationClass) {

            super(database, collectionName, documentBuilder, botConfigurationClass, PACKAGES_FIELD);
        }

        List<IResourceStore.IResourceId> getBotIdsContainingPackageUri(String packageId, Integer packageVersion)
                throws IResourceStore.ResourceNotFoundException {

            String searchedForPackageUri = String.join("",
                    packageResourceURI, packageId, versionQueryParam, String.valueOf(packageVersion));
            Document filter = new Document(PACKAGES_FIELD,
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

        List<IResourceStore.IResourceId> getBotIdsContainingPackage(String packageId, Integer packageVersion)
                throws IResourceStore.ResourceNotFoundException {
            return resourceStorage.getBotIdsContainingPackageUri(packageId, packageVersion);
        }
    }
}
