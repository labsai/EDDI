package ai.labs.resources.impl.deployment.mongo;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.deployment.IDeploymentStore;
import ai.labs.resources.rest.deployment.model.DeploymentInfo;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import javax.inject.Inject;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
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
            Document replacedDocument = collection.findOneAndReplace(filter, newDeploymentInfo);

            if (replacedDocument == null) {
                collection.insertOne(newDeploymentInfo);
            }
        }

        List<DeploymentInfo> readDeploymentInfos() throws IResourceStore.ResourceStoreException {
            List<DeploymentInfo> deploymentInfos = new LinkedList<>();

            try {
                FindIterable<Document> documents = collection.find();
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
