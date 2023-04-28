package ai.labs.eddi.configs.deployment.mongo;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.client.model.Indexes;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.reactivex.rxjava3.core.Observable;
import org.bson.Document;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static com.mongodb.client.model.Filters.eq;

/**
 * @author ginccc
 */
@ApplicationScoped
public class DeploymentStore implements IDeploymentStore {
    private static final String COLLECTION_DEPLOYMENTS = "deployments";
    public static final String FIELD_DEPLOYMENT_STATUS = "deploymentStatus";
    public static final String FIELD_ENVIRONMENT = "environment";
    public static final String FIELD_BOT_ID = "botId";
    public static final String FIELD_BOT_VERSION = "botVersion";
    private final MongoCollection<Document> deploymentsCollection;
    private final IDocumentBuilder documentBuilder;
    private final DeploymentResourceStore deploymentResourceStore;

    @Inject
    public DeploymentStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");
        this.deploymentsCollection = database.getCollection(COLLECTION_DEPLOYMENTS);
        this.documentBuilder = documentBuilder;
        this.deploymentResourceStore = new DeploymentResourceStore();
        Observable.fromPublisher(
                deploymentsCollection.createIndex(
                        Indexes.ascending(FIELD_DEPLOYMENT_STATUS, FIELD_ENVIRONMENT, FIELD_BOT_ID, FIELD_BOT_VERSION))
        ).blockingFirst();
    }

    @Override
    public DeploymentInfo getDeploymentInfo(String environment, String botId, Integer botVersion)
            throws IResourceStore.ResourceStoreException {

        return deploymentResourceStore.readDeploymentInfo(environment, botId, botVersion);
    }

    @Override
    public void setDeploymentInfo(String environment, String botId, Integer botVersion,
                                  DeploymentInfo.DeploymentStatus deploymentStatus) {
        deploymentResourceStore.setDeploymentInfo(environment, botId, botVersion, deploymentStatus);
    }

    @Override
    public List<DeploymentInfo> readDeploymentInfos() throws IResourceStore.ResourceStoreException {
        return deploymentResourceStore.readDeploymentInfos();
    }

    @Override
    public List<DeploymentInfo> readDeploymentInfos(DeploymentInfo.DeploymentStatus deploymentStatus)
            throws IResourceStore.ResourceStoreException {
        return deploymentResourceStore.readDeploymentInfos(deploymentStatus.toString());
    }

    private class DeploymentResourceStore {
        void setDeploymentInfo(String environment, String botId, Integer botVersion,
                               DeploymentInfo.DeploymentStatus deploymentStatus) {
            Document filter = createFilter(environment, botId, botVersion);
            Document newDeploymentInfo = new Document(filter);
            newDeploymentInfo.put(FIELD_DEPLOYMENT_STATUS, deploymentStatus.toString());
            try {
                Observable.fromPublisher(
                        deploymentsCollection.findOneAndReplace(filter, newDeploymentInfo)).blockingFirst();
            } catch (NoSuchElementException ne) {
                Observable.fromPublisher(
                        deploymentsCollection.insertOne(newDeploymentInfo)).blockingFirst();
            }
        }

        List<DeploymentInfo> readDeploymentInfos(String deploymentStatus) throws IResourceStore.ResourceStoreException {
            List<DeploymentInfo> deploymentInfos = new ArrayList<>();

            try {
                var filter = eq(FIELD_DEPLOYMENT_STATUS, deploymentStatus);
                var documents =
                        Observable.fromPublisher(
                                        deploymentStatus != null ?
                                                deploymentsCollection.find(filter) : deploymentsCollection.find())
                                .blockingIterable();
                for (var document : documents) {
                    deploymentInfos.add(documentBuilder.build(document, DeploymentInfo.class));
                }

                return deploymentInfos;
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }

        public DeploymentInfo readDeploymentInfo(String environment, String botId, Integer botVersion)
                throws IResourceStore.ResourceStoreException {

            try {
                var document = Observable.fromPublisher(
                        deploymentsCollection.find(createFilter(environment, botId, botVersion)
                        ).first()).blockingFirst();

                return documentBuilder.build(document, DeploymentInfo.class);
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }

        public List<DeploymentInfo> readDeploymentInfos() throws IResourceStore.ResourceStoreException {
            return readDeploymentInfos(null);
        }
    }

    private static Document createFilter(String environment, String botId, Integer botVersion) {
        var filter = new Document();
        filter.put(FIELD_ENVIRONMENT, environment);
        filter.put(FIELD_BOT_ID, botId);
        filter.put(FIELD_BOT_VERSION, botVersion);
        return filter;
    }
}
