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
import static com.mongodb.client.model.Filters.in;

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
    /**
     * Aggregation-only field names used by
     * {@link #removeDuplicateDeploymentRows()}.
     */
    private static final String FIELD_DUPLICATE_IDS = "duplicateIds";
    private static final String FIELD_DUPLICATE_COUNT = "duplicateCount";

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
     * The index matters because it is what actually closes the race.
     * {@code replaceOne(upsert)} is atomic per operation, but an upsert whose
     * filter fields are not uniquely indexed can still insert twice under
     * concurrency: two callers whose filter matches nothing both take the insert
     * branch. Only the unique index turns the loser into a duplicate-key error.
     * Without it {@code deleteDeploymentInfo} (which deletes ONE row) and
     * {@code readDeploymentInfos} (which returns whatever is there) let an agent
     * list twice, survive its own undeploy, and read as 'deployed' and 'undeployed'
     * at the same time.
     * </p>
     *
     * <p>
     * {@code createIndex(unique)} fails with E11000 against a collection that
     * ALREADY holds duplicates — exactly the state of the deployments this index
     * exists to protect, because those duplicates are what the check-then-act
     * {@link #setDeploymentInfo} replaced used to write. Letting that failure out
     * broke bean construction on precisely the installations that hit the bug, and
     * an unconstructable {@code IDeploymentStore} takes {@code RestAgentStore},
     * {@code RestAgentAdministration} and the startup redeploy with it. So the
     * duplicates are removed first — one row per (environment, agentId,
     * agentVersion), the most recently written kept — and the index is retried. If
     * even that does not get the index built, the collection keeps working and the
     * failure is reported at ERROR: the race is then genuinely still open, which an
     * operator has to know rather than read a comment claiming it is closed.
     * </p>
     */
    private void createDeploymentKeyIndex() {
        try {
            createUniqueKeyIndex();
            return;
        } catch (MongoException e) {
            LOGGER.warnf("Could not create the unique deployment-key index on '%s' (%s, %s, %s): %s. "
                    + "Removing duplicate rows and retrying.", COLLECTION_DEPLOYMENTS, FIELD_ENVIRONMENT, FIELD_AGENT_ID,
                    FIELD_AGENT_VERSION, e.getMessage());
        }

        int removed;
        try {
            removed = removeDuplicateDeploymentRows();
        } catch (Exception e) {
            // Nothing in this constructor may make the bean unconstructable — see
            // above. A dedupe that cannot run leaves the collection exactly as it
            // was, minus the index.
            LOGGER.errorf("Could not deduplicate '%s': %s. Duplicate deployment records are NOT prevented on this "
                    + "installation: an agent can list twice, survive its own undeploy, and read as both deployed and "
                    + "undeployed. Keep one row per %s/%s/%s by hand, then restart.", COLLECTION_DEPLOYMENTS, e.getMessage(),
                    FIELD_ENVIRONMENT, FIELD_AGENT_ID, FIELD_AGENT_VERSION);
            return;
        }

        try {
            createUniqueKeyIndex();
            LOGGER.warnf("Removed %d duplicate deployment row(s) from '%s' and created the unique index.", removed,
                    COLLECTION_DEPLOYMENTS);
        } catch (Exception e) {
            LOGGER.errorf("Removed %d duplicate deployment row(s) from '%s' but still could not create the unique index: %s. "
                    + "Duplicate deployment records are NOT prevented on this installation.", removed, COLLECTION_DEPLOYMENTS,
                    e.getMessage());
        }
    }

    private void createUniqueKeyIndex() {
        deploymentsCollection.createIndex(Indexes.ascending(FIELD_ENVIRONMENT, FIELD_AGENT_ID, FIELD_AGENT_VERSION),
                new IndexOptions().unique(true));
    }

    /**
     * Keeps one row per (environment, agentId, agentVersion) and removes the rest.
     *
     * <p>
     * The survivor is the newest row: duplicates differ only in
     * {@code deploymentStatus}, so the last-written one is what reflects the
     * operator's last deploy/undeploy. Guessing is unavoidable here — the rows
     * carry no timestamp of their own — but any single row is a consistent answer
     * where two are not.
     * </p>
     *
     * <p>
     * "Newest" is established by the leading {@code $sort} on {@code _id}, and that
     * stage is load-bearing rather than cosmetic. {@code $push} preserves the order
     * the documents reach {@code $group} in, and without a sort that is the storage
     * engine's natural order, which is not insertion order and is not stable
     * between nodes. Two nodes deduplicating the same collection during a rolling
     * restart could therefore each keep a DIFFERENT element and, between them,
     * delete every row for a key — an agent that was deployed silently never
     * redeployed by {@code checkDeployments} again. Rows are inserted without an
     * explicit {@code _id}, so Mongo assigns an ObjectId whose leading bytes are
     * the insert timestamp: ascending {@code _id} is insertion order, and every
     * node computes the same survivor.
     * </p>
     *
     * @return how many rows were removed
     */
    private int removeDuplicateDeploymentRows() {
        List<Document> pipeline = List.of(
                new Document("$sort", new Document("_id", 1)),
                new Document("$group", new Document("_id",
                        new Document(FIELD_ENVIRONMENT, "$" + FIELD_ENVIRONMENT).append(FIELD_AGENT_ID, "$" + FIELD_AGENT_ID)
                                .append(FIELD_AGENT_VERSION, "$" + FIELD_AGENT_VERSION))
                        .append(FIELD_DUPLICATE_IDS, new Document("$push", "$_id"))
                        .append(FIELD_DUPLICATE_COUNT, new Document("$sum", 1))),
                new Document("$match", new Document(FIELD_DUPLICATE_COUNT, new Document("$gt", 1))));

        List<Object> doomed = new ArrayList<>();
        for (Document group : deploymentsCollection.aggregate(pipeline)) {
            List<Object> ids = group.getList(FIELD_DUPLICATE_IDS, Object.class);
            if (ids == null || ids.size() < 2) {
                continue;
            }
            doomed.addAll(ids.subList(0, ids.size() - 1));
        }

        if (doomed.isEmpty()) {
            return 0;
        }
        return (int) deploymentsCollection.deleteMany(in("_id", doomed)).getDeletedCount();
    }

    /**
     * Upserts in ONE atomic operation.
     *
     * <p>
     * This was findOneAndReplace-then-insert-if-absent: a check-then-act, so two
     * callers racing on the same (environment, agentId, agentVersion) — a
     * double-clicked deploy, two nodes running their startup redeploy, a deploy
     * overlapping the 10-second {@code checkDeployments} sweep — both saw
     * {@code null} and both inserted. {@code replaceOne(upsert)} removes the
     * application-side window, but it does not on its own make the write unique: an
     * upsert whose filter matches nothing is still free to insert, and two of them
     * running concurrently both can. The unique index from
     * {@link #createDeploymentKeyIndex()} is what turns the second insert into a
     * duplicate-key error — so where that index could not be built, this race stays
     * open and is reported at ERROR rather than assumed away.
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
