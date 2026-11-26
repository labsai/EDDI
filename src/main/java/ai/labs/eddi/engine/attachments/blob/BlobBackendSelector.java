/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Which {@link BlobBackend} the attachment stores write large blobs to, and
 * which one reads a given {@link BlobLocator}.
 * <p>
 * {@code eddi.attachments.blob.backend} names the backend for new writes:
 * {@code auto} (the default: {@code gridfs} on MongoDB, {@code pg-chunks} on
 * PostgreSQL), {@code gridfs} or {@code pg-chunks}. A backend that cannot run
 * on the configured database fails startup with the reason, and so does a
 * backend that is planned but not shipped ({@code s3}, {@code pg-largeobject}).
 * <p>
 * Reads go by the backend a locator names, not by the configured one, so a
 * changed setting affects new writes only. A locator naming a backend this
 * deployment cannot reach is a store failure, never "not found".
 * <p>
 * Produced by {@code DataStoreProducers}, which knows the database; checked at
 * startup by {@link BlobBackendStartupCheck}. A backend is created only when it
 * is first used, so the bean of the other database is never touched.
 */
public class BlobBackendSelector {

    public static final String AUTO = "auto";
    public static final String GRIDFS = "gridfs";
    public static final String PG_CHUNKS = "pg-chunks";
    private static final String POSTGRES = "postgres";

    private final String writeBackend;
    private final Map<String, Supplier<BlobBackend>> available;

    /**
     * @param writeBackend
     *            the backend new large blobs go to; must be one of
     *            {@code available}
     * @param available
     *            the backends this deployment can reach, by name, created on first
     *            use
     */
    public BlobBackendSelector(String writeBackend, Map<String, Supplier<BlobBackend>> available) {
        this.available = Map.copyOf(available);
        if (!this.available.containsKey(writeBackend)) {
            throw new IllegalStateException("Blob backend '" + writeBackend + "' is not available on this deployment");
        }
        this.writeBackend = writeBackend;
    }

    /** A selector over one backend, which writes and reads — for tests. */
    public static BlobBackendSelector of(BlobBackend backend) {
        Objects.requireNonNull(backend, "backend");
        return new BlobBackendSelector(backend.name(), Map.of(backend.name(), () -> backend));
    }

    /**
     * The backend {@code configured} resolves to on {@code datastoreType}.
     *
     * @param configured
     *            the value of {@code eddi.attachments.blob.backend}
     * @param datastoreType
     *            the value of {@code eddi.datastore.type}: {@code postgres}, or
     *            anything else for MongoDB (as {@code DataStoreProducers} reads it)
     * @throws IllegalStateException
     *             for a combination that cannot run, naming the reason
     */
    public static String resolve(String configured, String datastoreType) {
        boolean postgres = POSTGRES.equals(datastoreType);
        String value = configured == null ? AUTO : configured.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "", AUTO -> postgres ? PG_CHUNKS : GRIDFS;
            case GRIDFS -> {
                if (postgres) {
                    throw new IllegalStateException(
                            "eddi.attachments.blob.backend=gridfs needs MongoDB, but eddi.datastore.type=postgres. "
                                    + "Use 'pg-chunks' or 'auto'.");
                }
                yield GRIDFS;
            }
            case PG_CHUNKS -> {
                if (!postgres) {
                    throw new IllegalStateException(
                            "eddi.attachments.blob.backend=pg-chunks needs PostgreSQL, but eddi.datastore.type is not 'postgres'. "
                                    + "Use 'gridfs' or 'auto'.");
                }
                yield PG_CHUNKS;
            }
            case "s3", "pg-largeobject" -> throw new IllegalStateException(
                    "eddi.attachments.blob.backend=" + value + " is not available in this version. Use 'auto', 'gridfs' or 'pg-chunks'.");
            default -> throw new IllegalStateException(
                    "Unknown eddi.attachments.blob.backend '" + configured + "'. Use 'auto', 'gridfs' or 'pg-chunks'.");
        };
    }

    /** The name of the backend new large blobs go to. */
    public String writeBackendName() {
        return writeBackend;
    }

    /** The backend new large blobs go to. */
    public BlobBackend forWrites() {
        return available.get(writeBackend).get();
    }

    /**
     * The backend that holds a locator's bytes.
     *
     * @throws IOException
     *             if this deployment cannot reach that backend
     */
    public BlobBackend forLocator(BlobLocator locator) throws IOException {
        Supplier<BlobBackend> backend = available.get(locator.backend());
        if (backend == null) {
            throw new IOException("Blob backend '" + locator.backend() + "' is not available on this deployment");
        }
        return backend.get();
    }
}
