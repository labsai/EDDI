/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Backend-agnostic store for {@link AgentTestRun} records, through
 * {@link IResourceStorageFactory} (MongoDB or PostgreSQL) at a single version —
 * the same pattern as {@code GroupConversationStore}.
 *
 * @since 6.5.0
 */
@ApplicationScoped
public class AgentTestRunStore implements IAgentTestRunStore {

    private static final Logger LOGGER = Logger.getLogger(AgentTestRunStore.class);

    public static final String COLLECTION_NAME = "agenttestruns";
    private static final int SINGLE_VERSION = 1;
    private static final String FIELD_AGENT_ID = "agentId";
    private static final String FIELD_STARTED_AT = "startedAt";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_HEARTBEAT_AT = "heartbeatAt";

    /**
     * Agent ids are hex ObjectIds or UUIDs. Anything else cannot have runs, and is
     * refused before it reaches either backend's query.
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final IResourceStorage<AgentTestRun> storage;

    @Inject
    public AgentTestRunStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        this.storage = storageFactory.create(COLLECTION_NAME, documentBuilder, AgentTestRun.class, FIELD_AGENT_ID, FIELD_STARTED_AT, FIELD_STATUS);
    }

    @Override
    public String create(AgentTestRun run) throws IResourceStore.ResourceStoreException {
        try {
            // The backend assigns the id on the first write (MongoDB has none before it).
            var resource = storage.newResource(run);
            storage.store(resource);
            String id = resource.getId();
            // Written again with the id inside, so a run read through any path carries it.
            storage.store(storage.newResource(id, SINGLE_VERSION, run.withId(id)));
            return id;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to create agent test run: " + e.getMessage(), e);
        }
    }

    @Override
    public void update(AgentTestRun run) throws IResourceStore.ResourceStoreException {
        if (run.id() == null) {
            throw new IllegalArgumentException("run.id is required to update a run");
        }
        try {
            storage.store(storage.newResource(run.id(), SINGLE_VERSION, run));
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to update agent test run: " + e.getMessage(), e);
        }
    }

    @Override
    public void updateIfHeartbeat(AgentTestRun run, long expectedHeartbeatAt)
            throws IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        if (run.id() == null) {
            throw new IllegalArgumentException("run.id is required to update a run");
        }
        try {
            storage.storeIfFieldEquals(storage.newResource(run.id(), SINGLE_VERSION, run), FIELD_HEARTBEAT_AT, expectedHeartbeatAt);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to update agent test run: " + e.getMessage(), e);
        }
    }

    @Override
    public AgentTestRun read(String runId) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        if (runId == null || !SAFE_ID.matcher(runId).matches()) {
            throw new IResourceStore.ResourceNotFoundException("Agent test run not found.");
        }
        try {
            var resource = storage.read(runId, SINGLE_VERSION);
            if (resource == null) {
                throw new IResourceStore.ResourceNotFoundException("Agent test run not found.");
            }
            return resource.getData().withId(runId);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to read agent test run: " + e.getMessage(), e);
        }
    }

    @Override
    public List<AgentTestRun> listByAgent(String agentId, int index, int limit) throws IResourceStore.ResourceStoreException {
        var results = new ArrayList<AgentTestRun>();
        for (var id : idsByAgent(agentId, index, limit)) {
            try {
                var resource = storage.read(id.getId(), SINGLE_VERSION);
                if (resource != null) {
                    results.add(resource.getData().withId(id.getId()));
                }
            } catch (IOException e) {
                LOGGER.warnf("Could not read agent test run %s: %s", sanitize(id.getId()), e.getMessage());
            }
        }
        return results;
    }

    @Override
    public List<AgentTestRun> listByStatus(AgentTestRun.Status status, int limit) throws IResourceStore.ResourceStoreException {
        var results = new ArrayList<AgentTestRun>();
        List<IResourceStore.IResourceId> ids;
        try {
            var filter = new IResourceFilter.QueryFilters(List.of(IResourceFilter.QueryFilter.exact(FIELD_STATUS, status.name())));
            ids = storage.findResources(new IResourceFilter.QueryFilters[]{filter}, FIELD_STARTED_AT, 0, limit);
        } catch (RuntimeException e) {
            throw new IResourceStore.ResourceStoreException("Failed to list agent test runs: " + e.getMessage(), e);
        }
        for (var id : ids) {
            try {
                var resource = storage.read(id.getId(), SINGLE_VERSION);
                if (resource != null) {
                    results.add(resource.getData().withId(id.getId()));
                }
            } catch (IOException e) {
                LOGGER.warnf("Could not read agent test run %s: %s", sanitize(id.getId()), e.getMessage());
            }
        }
        return results;
    }

    @Override
    public int pruneOlderRuns(String agentId, int keep) throws IResourceStore.ResourceStoreException {
        if (keep < 1) {
            keep = 1;
        }
        int deleted = 0;
        // Bounded sweep: anything beyond one page of surplus is caught by the next run.
        for (var id : idsByAgent(agentId, keep, IResourceStorage.MAX_RESULT_LIMIT)) {
            storage.removeAllPermanently(id.getId());
            deleted++;
        }
        return deleted;
    }

    private List<IResourceStore.IResourceId> idsByAgent(String agentId, int index, int limit) throws IResourceStore.ResourceStoreException {
        if (agentId == null || !SAFE_ID.matcher(agentId).matches()) {
            return List.of();
        }
        try {
            var filter = new IResourceFilter.QueryFilters(List.of(IResourceFilter.QueryFilter.exact(FIELD_AGENT_ID, agentId)));
            return storage.findResources(new IResourceFilter.QueryFilters[]{filter}, FIELD_STARTED_AT, Math.max(index, 0), limit);
        } catch (RuntimeException e) {
            throw new IResourceStore.ResourceStoreException("Failed to list agent test runs: " + e.getMessage(), e);
        }
    }
}
