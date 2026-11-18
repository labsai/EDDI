/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import ai.labs.eddi.datastore.mongo.GridFsBlobBackend;
import ai.labs.eddi.datastore.postgres.PgChunksBlobBackend;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.InputStream;

/**
 * The child-JVM side of
 * {@link BlobBackendContract#roundTripsWithBoundedHeapInAChildJvm}: puts a
 * generated stream into a real backend and reads it back, under the small heap
 * the parent gave this JVM. Prints {@code OK <sha256>} and exits 0 on success.
 * <p>
 * Arguments:
 * {@code <sizeBytes> <chunkBytes> gridfs <connectionString> <database>} or
 * {@code <sizeBytes> <chunkBytes> pg-chunks <jdbcUrl> <user> <password>}.
 */
public final class BlobBackendHeapProbe {

    static final long SEED = 0x5EED_B10BL;

    private BlobBackendHeapProbe() {
    }

    public static void main(String[] args) throws Exception {
        long size = Long.parseLong(args[0]);
        int chunkBytes = Integer.parseInt(args[1]);
        MongoClient mongo = null;
        BlobBackend backend;
        switch (args[2]) {
            case GridFsBlobBackend.NAME -> {
                mongo = MongoClients.create(args[3]);
                backend = new GridFsBlobBackend(mongo.getDatabase(args[4]), chunkBytes);
            }
            case PgChunksBlobBackend.NAME -> {
                PGSimpleDataSource dataSource = new PGSimpleDataSource();
                dataSource.setUrl(args[3]);
                dataSource.setUser(args[4]);
                dataSource.setPassword(args[5]);
                backend = new PgChunksBlobBackend(() -> dataSource, chunkBytes);
            }
            default -> throw new IllegalArgumentException("unknown backend " + args[2]);
        }
        try {
            String key = BlobKeys.random();
            long start = System.nanoTime();
            try (InputStream upload = new SeededInputStream(SEED, size)) {
                backend.put(key, upload, size, size);
            }
            long put = System.nanoTime();
            String read;
            try (InputStream in = backend.get(key)) {
                read = SeededInputStream.sha256(in);
            }
            long get = System.nanoTime();
            backend.delete(key);
            System.out.println("put " + (put - start) / 1_000_000 + " ms, get " + (get - put) / 1_000_000 + " ms");
            System.out.println("OK " + read);
        } finally {
            if (mongo != null) {
                mongo.close();
            }
        }
        System.exit(0);
    }
}
