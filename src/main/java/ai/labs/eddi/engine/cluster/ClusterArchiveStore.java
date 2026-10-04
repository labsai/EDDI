/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.ObjectStore;
import io.nats.client.ObjectStoreManagement;
import io.nats.client.api.ObjectInfo;
import io.nats.client.api.ObjectStoreConfiguration;
import io.nats.client.api.StorageType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Cluster mode: files a node writes for a later request — an exported agent
 * archive — that the load balancer may send to any other node. They are copied
 * to a JetStream object store ({@code <prefix>_ARCHIVES}, kept for the given
 * retention), and a node that does not have the file fetches it from there.
 * <p>
 * Best-effort on the way in (a failure leaves the file node-local, as on a
 * single node) and a plain miss on the way out. Single node: disabled, nothing
 * of NATS is touched.
 */
@ApplicationScoped
public class ClusterArchiveStore {

    private static final Logger LOGGER = Logger.getLogger(ClusterArchiveStore.class);
    static final String BUCKET = "ARCHIVES";

    private final ClusterConfig config;
    private final Instance<NatsConnectionManager> connections;
    private volatile Duration ensuredFor;

    @Inject
    public ClusterArchiveStore(ClusterConfig config, Instance<NatsConnectionManager> connections) {
        this.config = config;
        this.connections = connections;
    }

    public boolean enabled() {
        return config.isNats();
    }

    private String bucket() {
        return config.natsPrefix() + "_" + BUCKET;
    }

    private ObjectStore store(Duration retention) throws IOException, JetStreamApiException {
        Connection connection = connections.get().requireConnected();
        if (ensuredFor == null) {
            ObjectStoreManagement management = connection.objectStoreManagement();
            try {
                management.getStatus(bucket());
            } catch (JetStreamApiException missing) {
                management.create(ObjectStoreConfiguration.builder(bucket()).storageType(StorageType.File)
                        .replicas(config.natsReplicas()).ttl(retention).build());
            }
            ensuredFor = retention;
        }
        return connection.objectStore(bucket());
    }

    /** Copies {@code file} to the shared store under {@code name}. Never throws. */
    public void publish(String name, Path file, Duration retention) {
        if (!enabled()) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            store(retention).put(name, in);
        } catch (Exception e) {
            LOGGER.warnf("Archive %s stays on this node only — the shared store is unavailable: %s", name, e.getMessage());
        }
    }

    /**
     * Fetches {@code name} into {@code target} when another node published it.
     *
     * @return whether the file is now at {@code target}
     */
    public boolean fetch(String name, Path target, Duration retention) {
        if (!enabled()) {
            return false;
        }
        Path partial = null;
        try {
            ObjectStore store = store(retention);
            ObjectInfo info = store.getInfo(name);
            if (info == null || info.isDeleted()) {
                return false;
            }
            Files.createDirectories(target.getParent());
            // A name of its own: two downloads of one archive that both miss the local
            // file would otherwise write the same ".part" file and corrupt each other.
            partial = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".part");
            try (OutputStream out = Files.newOutputStream(partial)) {
                store.get(name, out);
            }
            try {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // Another download of the same archive finished first (an atomic replace of
                // a file that is being read fails on some file systems): its copy is whole.
                if (!Files.exists(target)) {
                    throw e;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            LOGGER.debugf("Archive %s not fetched from the shared store: %s", name, e.getMessage());
            try {
                if (partial != null) {
                    Files.deleteIfExists(partial);
                }
            } catch (IOException ignored) {
                // a leftover .part is swept with the archive directory
            }
            return false;
        }
    }
}
