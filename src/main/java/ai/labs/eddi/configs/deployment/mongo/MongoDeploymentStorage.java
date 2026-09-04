/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.deployment.mongo;

import ai.labs.eddi.configs.deployment.IDeploymentStorage;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import io.quarkus.arc.DefaultBean;
import org.bson.Document;
import org.jboss.logging.Logger;

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

    private static final Logger LOGGER = Logger.getLogger(MongoDeploymentStorage.class);

    private static final String COLLECTION_DEPLOYMENTS = "deployments";
    private static final String FIELD_DEPLOYMENT_STATUS = "deploymentStatus";
    private static final String FIELD_ENVIRONMENT = "environment";
    private static final String FIELD_AGENT_ID = "agentId";
    private static final String FIELD_AGENT_VERSION = "agentVersion";

    private final MongoCollection<Document> deploymentsCollection;
    private final IDocumentBuilder documentBuilder;

    @Inject
    public MongoDeploymentStorage(MongoDatabase database, IDocumentBuilder documentBuilder) {
        this.deploymentsCollection = database.getCollection(COLLECTION_DEPLOYMENTS);
        this.documentBuilder = documentBuilder;
        deploymentsCollection.createIndex(Indexes.ascending(FIELD_DEPLOYMENT_STATUS, FIELD_ENVIRONMENT, FIELD_AGENT_ID, FIELD_AGENT_VERSION));
        // deleteDeploymentInfos filters on agentId alone, which the index above cannot
        // serve: agentId is not one of its leading fields.
        deploymentsCollection.createIndex(Indexes.ascending(FIELD_AGENT_ID));
        createDeploymentKeyIndex();
    }

    /**
     * Adds the uniqueness {@code PostgresDeploymentStorage} gets from its ON
     * CONFLICT target — without refusing to start where it cannot be added.
     *
     * <p>
     * The index matters because {@code deleteDeploymentInfo} deletes ONE row and
     * {@code readDeploymentInfos} returns whatever is there: a duplicated key makes
     * an agent list twice, survive its own undeploy, and read as 'deployed' and
     * 'undeployed' at the same time.
     * </p>
     *
     * <p>
     * But {@code createIndex(unique)} fails with E11000 against a collection that
     * ALREADY holds duplicates — which is exactly the state of the deployments this
     * index exists to protect, because those duplicates are what the check-then-act
     * {@link #setDeploymentInfo} replaced used to write. Building it
     * unconditionally in the constructor therefore broke bean construction on
     * precisely the installations that hit the bug, and an unconstructable
     * {@code IDeploymentStore} takes {@code RestAgentStore},
     * {@code RestAgentAdministration} and the startup redeploy with it. So a
     * failure is logged with the cleanup an operator has to do, and the collection
     * keeps working without the index: {@code replaceOne(upsert)} is the actual fix
     * for the race, the index only its backstop.
     * </p>
     */
    private void createDeploymentKeyIndex() {
        try {
            deploymentsCollection.createIndex(Indexes.ascending(FIELD_ENVIRONMENT, FIELD_AGENT_ID, FIELD_AGENT_VERSION),
                    new IndexOptions().unique(true));
        } catch (MongoException e) {
            LOGGER.warnf("Could not create the unique deployment-key index on '%s' (%s, %s, %s): %s. "
                    + "Deployments keep working, but duplicate rows are no longer prevented. The usual cause is "
                    + "duplicates already in the collection: keep one row per environment/agentId/agentVersion, "
                    + "then restart to have the index created.",
                    COLLECTION_DEPLOYMENTS, FIELD_ENVIRONMENT, FIELD_AGENT_ID, FIELD_AGENT_VERSION, e.getMessage());
        }
    }

    /**
     * Upserts in ONE atomic operation.
     *
     * <p>
     * This was findOneAndReplace-then-insert-if-absent: a check-then-act, so two
     * callers racing on the same (environment, agentId, agentVersion) — a
     * double-clicked deploy, two nodes running their startup redeploy, a deploy
     * overlapping the 10-second {@code checkDeployments} sweep — both saw
     * {@code null} and both inserted. {@code replaceOne(upsert)} is decided by the
     * server, so this alone closes the race; where
     * {@link #createDeploymentKeyIndex()} succeeded, the unique index additionally
     * turns any residual one into a duplicate-key error rather than a second row.
     * </p>
     */
    @Override
    public void setDeploymentInfo(String environment, String agentId, Integer agentVersion, DeploymentInfo.DeploymentStatus deploymentStatus) {
        Document filter = createFilter(environment, agentId, agentVersion);
        Document newDeploymentInfo = new Document(filter);
        newDeploymentInfo.put(FIELD_DEPLOYMENT_STATUS, deploymentStatus.toString());

        deploymentsCollection.replaceOne(filter, newDeploymentInfo, new ReplaceOptions().upsert(true));
    }

    @Override
    public DeploymentInfo readDeploymentInfo(String environment, String agentId, Integer agentVersion) throws IResourceStore.ResourceStoreException {
        try {
            var document = deploymentsCollection.find(createFilter(environment, agentId, agentVersion)).first();
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
    public List<DeploymentInfo> readDeploymentInfos(String deploymentStatus) throws IResourceStore.ResourceStoreException {
        List<DeploymentInfo> deploymentInfos = new ArrayList<>();
        try {
            var iterable = deploymentStatus != null
                    ? deploymentsCollection.find(eq(FIELD_DEPLOYMENT_STATUS, deploymentStatus))
                    : deploymentsCollection.find();
            for (var document : iterable) {
                deploymentInfos.add(documentBuilder.build(document, DeploymentInfo.class));
            }
            return deploymentInfos;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public int deleteDeploymentInfos(String agentId) {
        return (int) deploymentsCollection.deleteMany(eq(FIELD_AGENT_ID, agentId)).getDeletedCount();
    }

    @Override
    public int deleteDeploymentInfo(String environment, String agentId, Integer agentVersion) {
        return (int) deploymentsCollection.deleteOne(createFilter(environment, agentId, agentVersion)).getDeletedCount();
    }

    private static Document createFilter(String environment, String agentId, Integer agentVersion) {
        var filter = new Document();
        filter.put(FIELD_ENVIRONMENT, environment);
        filter.put(FIELD_AGENT_ID, agentId);
        filter.put(FIELD_AGENT_VERSION, agentVersion);
        return filter;
    }
}
