/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import java.util.HashMap;
import jakarta.annotation.PostConstruct;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.cluster.events.IClusterEventBus;
import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.engine.runtime.IWorkflowFactory;
import ai.labs.eddi.engine.runtime.client.workflows.IWorkflowStoreClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author ginccc
 */
@ApplicationScoped
public class WorkflowFactory implements IWorkflowFactory {

    /**
     * Built workflows, keyed by an immutable {@code (id, version)} record.
     * <p>
     * Configuration versions never change once written, so an entry is only ever
     * invalid after its version was deleted — {@link #evict} handles that, here and
     * (cluster mode) on every node through the {@code config.deleted} event.
     * <p>
     * The build used to run as {@code containsKey} → {@code synchronized} → build →
     * {@code put}: two threads that both saw the key missing built the same
     * workflow twice (the lock only serialised them), and every build of every
     * workflow waited on that one global lock. Now the first caller of a key builds
     * it, outside any lock, and concurrent callers of the same key wait for that
     * one build; a failed build is not cached. The old key class also threw a
     * NullPointerException from {@code equals} for a null version.
     */
    private final Map<WorkflowKey, CompletableFuture<IExecutableWorkflow>> executableWorkflows = new ConcurrentHashMap<>();
    private final IWorkflowStoreClientLibrary workflowStoreClientLibrary;

    /**
     * Cluster mode: deletions are announced; null in tests built with {@code new}.
     */
    @Inject
    IClusterEventBus clusterEvents;

    @Inject
    IComponentCache componentCache;

    @Inject
    public WorkflowFactory(IWorkflowStoreClientLibrary workflowStoreClientLibrary) {
        this.workflowStoreClientLibrary = workflowStoreClientLibrary;
    }

    @PostConstruct
    void subscribeToCluster() {
        if (clusterEvents != null) {
            clusterEvents.subscribe(ClusterEvent.CONFIG_DELETED, event -> {
                if ("workflow".equals(event.getString("type"))) {
                    Object version = event.get("version");
                    evictLocal(event.getString("id"), version instanceof Number n ? n.intValue() : null);
                }
            });
            clusterEvents.onResync(executableWorkflows::clear);
        }
    }

    @Override
    public IExecutableWorkflow getExecutableWorkflow(final String workflowId, final Integer workflowVersion) throws ServiceException {
        WorkflowKey key = new WorkflowKey(workflowId, workflowVersion);
        CompletableFuture<IExecutableWorkflow> existing = executableWorkflows.get(key);
        if (existing == null) {
            CompletableFuture<IExecutableWorkflow> mine = new CompletableFuture<>();
            existing = executableWorkflows.putIfAbsent(key, mine);
            if (existing == null) {
                // This thread won: build OUTSIDE any lock, so a slow build blocks only the
                // callers of this one workflow version.
                try {
                    mine.complete(workflowStoreClientLibrary.getExecutableWorkflow(workflowId, workflowVersion));
                } catch (ServiceException | RuntimeException e) {
                    executableWorkflows.remove(key, mine);
                    mine.completeExceptionally(e);
                } catch (Error e) {
                    // A LinkageError / StackOverflowError while building must not leave an
                    // incomplete future in the map: every later caller of this key would
                    // block on it for ever.
                    executableWorkflows.remove(key, mine);
                    mine.completeExceptionally(e);
                    throw e;
                }
                existing = mine;
            }
        }
        try {
            return existing.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof ServiceException serviceException) {
                throw serviceException;
            }
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    /**
     * Forgets a deleted workflow version on this node and announces it to the
     * others. A {@code null} version evicts every version of the workflow.
     */
    @Override
    public void evict(String workflowId, Integer workflowVersion) {
        evictLocal(workflowId, workflowVersion);
        if (clusterEvents != null) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "workflow");
            payload.put("id", workflowId);
            payload.put("version", workflowVersion);
            clusterEvents.publish(ClusterEvent.CONFIG_DELETED, payload);
        }
    }

    void evictLocal(String workflowId, Integer workflowVersion) {
        if (workflowId == null) {
            return;
        }
        executableWorkflows.keySet()
                .removeIf(key -> workflowId.equals(key.id()) && (workflowVersion == null || workflowVersion.equals(key.version())));
        if (componentCache != null) {
            componentCache.evictWorkflow(workflowId, workflowVersion);
        }
    }

    /** Number of built workflows held — for tests. */
    int size() {
        return executableWorkflows.size();
    }

    private record WorkflowKey(String id, Integer version) {
    }
}
