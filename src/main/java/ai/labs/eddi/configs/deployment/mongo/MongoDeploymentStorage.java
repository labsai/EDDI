package ai.labs.eddi.configs.deployment.mongo;

import ai.labs.eddi.configs.deployment.IDeploymentStorage;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import io.quarkus.arc.DefaultBean;
import org.bson.Document;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;

/**
 * MongoDB implementation of {@link IDeploymentStorage}.
 */
@ApplicationScoped
@DefaultBean
public class MongoDeploymentStorage implements IDeploymentStorage {

    private static final String COLLECTION_DEPLOYMENTS = "deployments";
    private static final String FIELD_DEPLOYMENT_STATUS = "deploymentStatus";
    private static final String FIELD_ENVIRONMENT = "environment";
    private static final String FIELD_BOT_ID = "botId";
    private static final String FIELD_BOT_VERSION = "botVersion";

    private final MongoCollection<Document> deploymentsCollection;
    private final IDocumentBuilder documentBuilder;

    @Inject
    public MongoDeploymentStorage(MongoDatabase database, IDocumentBuilder documentBuilder) {
        this.deploymentsCollection = database.getCollection(COLLECTION_DEPLOYMENTS);
        this.documentBuilder = documentBuilder;
        deploymentsCollection.createIndex(
                Indexes.ascending(FIELD_DEPLOYMENT_STATUS, FIELD_ENVIRONMENT, FIELD_BOT_ID, FIELD_BOT_VERSION));
    }

    @Override
    public void setDeploymentInfo(String environment, String botId, Integer botVersion,
                                   DeploymentInfo.DeploymentStatus deploymentStatus) {
        Document filter = createFilter(environment, botId, botVersion);
        Document newDeploymentInfo = new Document(filter);
        newDeploymentInfo.put(FIELD_DEPLOYMENT_STATUS, deploymentStatus.toString());

        Document existing = deploymentsCollection.findOneAndReplace(filter, newDeploymentInfo);
        if (existing == null) {
            deploymentsCollection.insertOne(newDeploymentInfo);
        }
    }

    @Override
    public DeploymentInfo readDeploymentInfo(String environment, String botId, Integer botVersion)
            throws IResourceStore.ResourceStoreException {
        try {
            var document = deploymentsCollection.find(
                    createFilter(environment, botId, botVersion)).first();
            if (document == null) {
                return null;
            }
            return documentBuilder.build(document, DeploymentInfo.class);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public List<DeploymentInfo> readDeploymentInfos() throws IResourceStore.ResourceStoreException {
        return readDeploymentInfos(null);
    }

    @Override
    public List<DeploymentInfo> readDeploymentInfos(String deploymentStatus)
            throws IResourceStore.ResourceStoreException {
        List<DeploymentInfo> deploymentInfos = new ArrayList<>();
        try {
            var iterable = deploymentStatus != null ?
                    deploymentsCollection.find(eq(FIELD_DEPLOYMENT_STATUS, deploymentStatus)) :
                    deploymentsCollection.find();
            for (var document : iterable) {
                deploymentInfos.add(documentBuilder.build(document, DeploymentInfo.class));
            }
            return deploymentInfos;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
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
