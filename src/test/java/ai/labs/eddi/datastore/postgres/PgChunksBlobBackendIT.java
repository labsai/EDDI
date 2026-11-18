/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.attachments.blob.BlobBackend;
import ai.labs.eddi.engine.attachments.blob.BlobBackendContract;
import ai.labs.eddi.engine.attachments.blob.BlobKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.file.FileAlreadyExistsException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PgChunksBlobBackend} against a real PostgreSQL (Testcontainers): the
 * shared {@link BlobBackendContract}, plus the table layout and the leftovers
 * of a crashed upload.
 */
@DisplayName("BlobBackend contract (pg-chunks)")
class PgChunksBlobBackendIT extends PostgresTestBase implements BlobBackendContract {

    private static final int CHUNK_BYTES = 64 * 1024;
    private static final DataSource DATA_SOURCE = createDataSource();

    private PgChunksBlobBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        backend = new PgChunksBlobBackend(() -> DATA_SOURCE, CHUNK_BYTES);
        backend.stat(BlobKeys.random()); // creates the table
        truncateTables(DATA_SOURCE, PgChunksBlobBackend.TABLE);
    }

    @Override
    public BlobBackend backend() {
        return backend;
    }

    @Override
    public BlobBackend backendWithChunkBytes(int chunkBytes) {
        return new PgChunksBlobBackend(() -> DATA_SOURCE, chunkBytes);
    }

    @Override
    public int chunkBytes() {
        return CHUNK_BYTES;
    }

    @Override
    public long storedRecordCount() throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT count(*) FROM attachment_chunks")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Override
    public List<String> heapProbeArgs() {
        PGSimpleDataSource pg = (PGSimpleDataSource) DATA_SOURCE;
        return List.of(PgChunksBlobBackend.NAME, pg.getUrl(), pg.getUser(), pg.getPassword());
    }

    /**
     * The production default, so the probe also bounds the driver's copies of a 4
     * MiB chunk.
     */
    @Override
    public int heapProbeChunkBytes() {
        return 4 * 1024 * 1024;
    }

    @Test
    void writesOneRowPerChunk() throws Exception {
        String key = BlobKeys.random();
        int size = 3 * CHUNK_BYTES + 17;

        backend.put(key, new ByteArrayInputStream(new byte[size]), size, size);

        assertEquals(4, storedRecordCount());
    }

    @Test
    void listsAndDeletesTheChunksOfACrashedUpload() throws Exception {
        String key = BlobKeys.random();
        // What a node that died mid-upload leaves: committed chunks, nothing else.
        try (Connection conn = DATA_SOURCE.getConnection();
                PreparedStatement ps = conn.prepareStatement("INSERT INTO attachment_chunks (blob_key, seq, data) VALUES (?, ?, ?)")) {
            for (int seq = 0; seq < 2; seq++) {
                ps.setString(1, key);
                ps.setInt(2, seq);
                ps.setBytes(3, new byte[CHUNK_BYTES]);
                ps.executeUpdate();
            }
        }

        try (Stream<String> keys = backend.list("")) {
            assertEquals(List.of(key), keys.toList());
        }
        assertThrows(FileAlreadyExistsException.class, () -> backend.put(key, new ByteArrayInputStream(new byte[]{9}), 1, 1));
        assertEquals(2, storedRecordCount(), "the refused put removed nothing");

        backend.delete(key);

        assertEquals(0, storedRecordCount());
    }

    @Test
    void keysTheTableByBlobAndSequenceInByteOrder() throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("""
                        SELECT a.attname, coalesce(c.collname, '')
                        FROM pg_index i
                        JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                        LEFT JOIN pg_collation c ON c.oid = a.attcollation
                        WHERE i.indrelid = 'attachment_chunks'::regclass AND i.indisprimary
                        ORDER BY a.attname""")) {
            rs.next();
            assertEquals("blob_key", rs.getString(1));
            assertEquals("C", rs.getString(2));
            rs.next();
            assertEquals("seq", rs.getString(1));
        }
    }

    @Test
    void refusesAChunkSizeOutsideItsBounds() {
        assertThrows(IllegalArgumentException.class, () -> new PgChunksBlobBackend(() -> DATA_SOURCE, 0));
        assertThrows(IllegalArgumentException.class, () -> new PgChunksBlobBackend(() -> DATA_SOURCE, PgChunksBlobBackend.MAX_CHUNK_BYTES + 1));
    }
}
