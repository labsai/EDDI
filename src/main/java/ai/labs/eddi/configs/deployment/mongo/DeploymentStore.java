package ai.labs.eddi.configs.deployment.mongo;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.reactivex.rxjava3.core.Observable;
import org.bson.Document;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * @author ginccc
 */
@ApplicationScoped
public class DeploymentStore implements IDeploymentStore {
    private static final String COLLECTION_DEPLOYMENTS = "deployments";
    private final MongoCollection<Document> collection;
    private final IDocumentBuilder documentBuilder;
    private DeploymentResourceStore deploymentResourceStore;

    @Inject
    public DeploymentStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");
        this.collection = database.getCollection(COLLECTION_DEPLOYMENTS);
        this.documentBuilder = documentBuilder;
        this.deploymentResourceStore = new DeploymentResourceStore();
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

    private class DeploymentResourceStore {
        void setDeploymentInfo(String environment, String botId, Integer botVersion,
                               DeploymentInfo.DeploymentStatus deploymentStatus) {
            Document filter = new Document();
            filter.put("environment", environment);
            filter.put("botId", botId);
            filter.put("botVersion", botVersion);
            Document newDeploymentInfo = new Document(filter);
            newDeploymentInfo.put("deploymentStatus", deploymentStatus.toString());
            try {
                Document replacedDocument = Observable.fromPublisher(collection.findOneAndReplace(filter, newDeploymentInfo)).blockingFirst();
            } catch (NoSuchElementException ne) {
                Observable.fromPublisher(collection.insertOne(newDeploymentInfo)).blockingFirst();
            }
        }

        List<DeploymentInfo> readDeploymentInfos() throws IResourceStore.ResourceStoreException {
            List<DeploymentInfo> deploymentInfos = new LinkedList<>();

            try {
                Iterable<Document> documents = Observable.fromPublisher(collection.find()).blockingIterable();
                for (Document document : documents) {
                    deploymentInfos.add(documentBuilder.build(document, DeploymentInfo.class));
                }

                return deploymentInfos;
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }
    }
}
