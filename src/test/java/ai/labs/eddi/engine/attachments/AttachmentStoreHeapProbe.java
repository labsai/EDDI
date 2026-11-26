/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.datastore.mongo.GridFsAttachmentStore;
import ai.labs.eddi.datastore.mongo.GridFsBlobBackend;
import ai.labs.eddi.datastore.postgres.PgChunksBlobBackend;
import ai.labs.eddi.datastore.postgres.PostgresAttachmentStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.Attachment;
import ai.labs.eddi.engine.attachments.blob.BlobBackend;
import ai.labs.eddi.engine.attachments.blob.BlobBackendSelector;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;

/**
 * The child-JVM side of
 * {@link StreamingAttachmentStoreContract#aLargeUploadRoundTripsWithBoundedHeapInAChildJvm}:
 * streams a generated upload through a real attachment store into its real blob
 * backend and reads it back with {@code open}, under the small heap the parent
 * gave this JVM. Prints {@code OK <sha256>} and exits 0 on success.
 * <p>
 * Arguments: {@code <sizeBytes> mongodb <connectionString> <database>} or
 * {@code <sizeBytes> postgres <jdbcUrl> <user> <password>}.
 */
public final class AttachmentStoreHeapProbe {

    static final long SEED = 0x5EED_A77AL;
    private static final String CONVERSATION = "6aba70011a25f8be7920e2ff";
    private static final String USER = "user-6aba70011a25f8ff";

    private AttachmentStoreHeapProbe() {
    }

    public static void main(String[] args) throws Exception {
        long size = Long.parseLong(args[0]);
        MongoClient mongo = null;
        IAttachmentStore store;
        BlobBackend backend;
        switch (args[1]) {
            case "mongodb" -> {
                mongo = MongoClients.create(args[2]);
                var database = mongo.getDatabase(args[3]);
                backend = new GridFsBlobBackend(database, 255 * 1024);
                GridFsAttachmentStore gridFs = new GridFsAttachmentStore(database);
                store = gridFs;
            }
            case "postgres" -> {
                PGSimpleDataSource dataSource = new PGSimpleDataSource();
                dataSource.setUrl(args[2]);
                dataSource.setUser(args[3]);
                dataSource.setPassword(args[4]);
                backend = new PgChunksBlobBackend(() -> dataSource, 4 * 1024 * 1024);
                store = new PostgresAttachmentStore(new FixedInstance(dataSource));
            }
            default -> throw new IllegalArgumentException("unknown database " + args[1]);
        }
        set(store, "maxSizeBytes", 1_048_576L);
        set(store, "blobBackends", BlobBackendSelector.of(backend));
        try {
            Attachment stored;
            try (InputStream upload = new PdfLikeStream(SEED, size)) {
                stored = store.store(upload, size, "application/pdf", "probe.pdf", CONVERSATION, "default", USER, null,
                        QuotaMode.LARGE_PER_USER);
            }
            String read;
            try (InputStream in = store.open(stored.storageRef(), CONVERSATION)) {
                read = PdfLikeStream.sha256(in);
            }
            store.delete(stored.storageRef(), CONVERSATION);
            System.out.println("OK " + read);
        } finally {
            if (mongo != null) {
                mongo.close();
            }
        }
        System.exit(0);
    }

    /** Sets a package-private configuration field of a store, as CDI would. */
    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** An {@link Instance} that only answers {@link #get()}. */
    private record FixedInstance(DataSource dataSource) implements Instance<DataSource> {

        @Override
        public DataSource get() {
            return dataSource;
        }

        @Override
        public Instance<DataSource> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends DataSource> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends DataSource> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return false;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public boolean isResolvable() {
            return true;
        }

        @Override
        public void destroy(DataSource instance) {
        }

        @Override
        public Handle<DataSource> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<DataSource>> handles() {
            return List.of();
        }

        @Override
        public Iterator<DataSource> iterator() {
            return List.of(dataSource).iterator();
        }
    }
}
