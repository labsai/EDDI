/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal.handoff;

import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.modules.llm.tools.quota.IToolQuotaStore;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaKey;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaStoreException;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaUsage;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * In-memory stand-ins for the stores the handoff pipeline runs against. The
 * workspace fake serializes through the production {@link JsonSerialization} on
 * every read and write, so a test sees what a database round trip would do to
 * the document (a lost field, a record that does not deserialize) and a reader
 * holds a private snapshot, which is what makes a lost compare-and-set
 * meaningful.
 */
final class HandoffFakes {

    private HandoffFakes() {
    }

    static class WorkspaceStore implements IGroupWorkspaceStore {

        private final JsonSerialization json = new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));
        private final Map<String, String> docs = new HashMap<>();
        /**
         * Runs once, just before the next CAS compares revisions: a concurrent writer.
         */
        Runnable beforeNextCas;
        boolean failReads;
        boolean failWrites;
        int casCalls;

        private GroupWorkspace copy(String doc) {
            try {
                return json.deserialize(doc, GroupWorkspace.class);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private String write(GroupWorkspace workspace) {
            try {
                return json.serialize(workspace);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public synchronized GroupWorkspace find(String groupId) throws IResourceStore.ResourceStoreException {
            if (failReads) {
                throw new IResourceStore.ResourceStoreException("read failed");
            }
            String doc = docs.get(groupId);
            return doc == null ? null : copy(doc);
        }

        @Override
        public synchronized GroupWorkspace readOrCreate(String groupId) throws IResourceStore.ResourceStoreException {
            if (failReads) {
                throw new IResourceStore.ResourceStoreException("read failed");
            }
            String doc = docs.get(groupId);
            if (doc == null) {
                GroupWorkspace fresh = new GroupWorkspace();
                fresh.setId(groupId);
                fresh.setGroupId(groupId);
                docs.put(groupId, write(fresh));
                doc = docs.get(groupId);
            }
            return copy(doc);
        }

        @Override
        public synchronized void deleteByGroupId(String groupId) {
            docs.remove(groupId);
        }

        @Override
        public boolean casRunningDiscussion(GroupWorkspace workspace) throws IResourceStore.ResourceStoreException {
            return casRevision(workspace);
        }

        @Override
        public boolean casRevision(GroupWorkspace workspace) throws IResourceStore.ResourceStoreException {
            Runnable interferer;
            synchronized (this) {
                interferer = beforeNextCas;
                beforeNextCas = null;
            }
            if (interferer != null) {
                interferer.run();
            }
            synchronized (this) {
                casCalls++;
                if (failWrites) {
                    throw new IResourceStore.ResourceStoreException("write failed");
                }
                GroupWorkspace stored = copy(docs.get(workspace.getGroupId()));
                if (!stored.getRevision().equals(workspace.getRevision())) {
                    return false;
                }
                workspace.setRevision(Integer.toString(Integer.parseInt(workspace.getRevision()) + 1));
                docs.put(workspace.getGroupId(), write(workspace));
                return true;
            }
        }

        /** Read-modify-write by a "concurrent" writer, retrying its own CAS. */
        synchronized void mutate(String groupId, Consumer<GroupWorkspace> change) throws IResourceStore.ResourceStoreException {
            GroupWorkspace ws = readOrCreate(groupId);
            change.accept(ws);
            Runnable saved = beforeNextCas;
            beforeNextCas = null;
            casRevision(ws);
            beforeNextCas = saved;
        }
    }

    static class QuotaStore implements IToolQuotaStore {

        final Map<String, Integer> counters = new HashMap<>();
        boolean fail;

        @Override
        public synchronized Acquisition tryAcquire(ToolQuotaKey key, int limit, Instant expiresAt) {
            if (fail) {
                throw new ToolQuotaStoreException("down", null);
            }
            int used = counters.getOrDefault(key.digest(), 0);
            if (used >= limit) {
                return new Acquisition(false, used);
            }
            counters.put(key.digest(), used + 1);
            return new Acquisition(true, used + 1);
        }

        @Override
        public synchronized void release(ToolQuotaKey key) {
            counters.computeIfPresent(key.digest(), (k, v) -> Math.max(0, v - 1));
        }

        @Override
        public synchronized int used(ToolQuotaKey key) {
            return counters.getOrDefault(key.digest(), 0);
        }

        @Override
        public long deleteBySubject(String subject) {
            return 0;
        }

        @Override
        public List<ToolQuotaUsage> findBySubject(String subject, int max) {
            return List.of();
        }

        @Override
        public int deleteExpired(Instant now) {
            return 0;
        }

        synchronized int total() {
            return counters.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
