/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.attachments.blob.BlobBackend;
import ai.labs.eddi.engine.attachments.blob.BlobKeys;
import ai.labs.eddi.engine.attachments.blob.BlobStat;
import ai.labs.eddi.engine.attachments.blob.BlobStreams;
import ai.labs.eddi.engine.attachments.blob.LengthCheckedInputStream;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.postgresql.PGStatement;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The {@code pg-chunks} {@link BlobBackend}: an object is a run of rows in
 * {@code attachment_chunks(blob_key, seq, data)}, each holding one chunk of
 * {@code eddi.attachments.blob.pg-chunks.chunk-bytes} (the last one shorter).
 * <p>
 * <b>Never one transaction per object.</b> Every chunk is committed on its own,
 * so a 500 MB upload neither holds a transaction open for its whole duration
 * nor sends the WAL one huge commit. The price is that a node dying mid-upload
 * leaves committed chunks behind: {@link #list} finds them and {@link #delete}
 * removes them. A {@code put} that fails while its node lives removes them
 * itself, on a fresh connection. Each chunk is inserted on a connection
 * borrowed for that insert only, so a slow uploader holds none while it sends
 * the next chunk. Because it commits as it goes, {@code put} must not run
 * inside a JTA transaction.
 * <p>
 * <b>Reads fetch one chunk per query</b>, each on its own pooled connection: a
 * single {@code SELECT … ORDER BY seq} would be read whole into memory by the
 * JDBC driver, and a cursor would hold a connection for as long as a slow
 * reader takes.
 * <p>
 * <b>Chunk size is per object.</b> A range read derives it from the object's
 * first chunk, never from the current setting, so changing the setting affects
 * new writes only.
 * <p>
 * The table is separate from {@code attachments}, whose {@code data} column is
 * untouched. This backend is not wired into the attachment store yet.
 */
@ApplicationScoped
public class PgChunksBlobBackend implements BlobBackend {

    /** The name a {@code BlobLocator} records for this backend. */
    public static final String NAME = "pg-chunks";
    static final String TABLE = "attachment_chunks";
    /** Bounds a configured chunk well below PostgreSQL's 1 GB {@code bytea}. */
    static final int MAX_CHUNK_BYTES = 256 * 1024 * 1024;

    // COLLATE "C" lets the primary key's index serve the prefix LIKE of list().
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS attachment_chunks (
                blob_key TEXT COLLATE "C" NOT NULL,
                seq INT NOT NULL,
                data BYTEA NOT NULL,
                PRIMARY KEY (blob_key, seq)
            )
            """;
    private static final String SCHEMA_LOCK = "SELECT pg_advisory_xact_lock(hashtextextended('eddi-attachment-chunks-schema', 0))";
    private static final String INSERT_FIRST = "INSERT INTO attachment_chunks (blob_key, seq, data) VALUES (?, ?, ?) ON CONFLICT DO NOTHING";
    private static final String INSERT_NEXT = "INSERT INTO attachment_chunks (blob_key, seq, data) VALUES (?, ?, ?)";
    private static final String SELECT_LAYOUT = "SELECT max(seq), max(CASE WHEN seq = 0 THEN octet_length(data) END) FROM attachment_chunks WHERE blob_key = ?";
    private static final String SELECT_CHUNK = "SELECT data FROM attachment_chunks WHERE blob_key = ? AND seq = ?";
    private static final String SELECT_STAT = "SELECT count(*), coalesce(sum(octet_length(data)), 0) FROM attachment_chunks WHERE blob_key = ?";
    private static final String DELETE = "DELETE FROM attachment_chunks WHERE blob_key = ?";
    // The prefix is validated to letters, digits and '-' (BlobKeys), so it holds no
    // LIKE wildcard.
    private static final String LIST = "SELECT DISTINCT blob_key FROM attachment_chunks WHERE blob_key LIKE ? ORDER BY blob_key";
    private static final int LIST_FETCH_SIZE = 500;

    private final Supplier<DataSource> dataSource;
    private final int chunkBytes;
    private volatile boolean schemaReady;

    @Inject
    public PgChunksBlobBackend(Instance<DataSource> dataSourceInstance,
            @ConfigProperty(name = "eddi.attachments.blob.pg-chunks.chunk-bytes", defaultValue = "4194304") int chunkBytes) {
        this(dataSourceInstance::get, chunkBytes);
    }

    /**
     * @param dataSource
     *            resolved on first use, so constructing the backend touches no
     *            database
     * @param chunkBytes
     *            the chunk size for new objects, 1 byte to 256 MiB
     */
    public PgChunksBlobBackend(Supplier<DataSource> dataSource, int chunkBytes) {
        if (chunkBytes < 1 || chunkBytes > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException(
                    "eddi.attachments.blob.pg-chunks.chunk-bytes must be between 1 and %d: %d".formatted(MAX_CHUNK_BYTES, chunkBytes));
        }
        this.dataSource = dataSource;
        this.chunkBytes = chunkBytes;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void put(String key, InputStream in, long declaredLength, long maxBytes) throws IOException {
        BlobKeys.requireValidKey(key);
        LengthCheckedInputStream source = new LengthCheckedInputStream(in, declaredLength, maxBytes);
        ensureSchema();
        long expected = declaredLength >= 0 ? declaredLength : maxBytes;
        byte[] buffer = new byte[(int) Math.max(1, Math.min(chunkBytes, expected))];
        boolean wroteFirstChunk = false;
        try {
            int seq = 0;
            while (true) {
                int n = source.readNBytes(buffer, 0, buffer.length);
                if (n == 0 && seq > 0) {
                    break;
                }
                byte[] data = n == buffer.length ? buffer : Arrays.copyOf(buffer, n);
                if (seq == 0) {
                    if (insertChunk(INSERT_FIRST, key, seq, data) == 0) {
                        throw new FileAlreadyExistsException("A blob with this key already exists");
                    }
                    wroteFirstChunk = true;
                } else {
                    insertChunk(INSERT_NEXT, key, seq, data);
                }
                seq++;
                if (n < buffer.length) {
                    break; // a short read means the source is exhausted
                }
            }
        } catch (IOException | SQLException | RuntimeException e) {
            IOException failure = asIOException(e);
            if (wroteFirstChunk) {
                removePartial(key, failure);
            }
            throw failure;
        }
    }

    @Override
    public InputStream get(String key) throws IOException {
        BlobKeys.requireValidKey(key);
        Layout layout = layout(key);
        return new ChunkInputStream(key, 0, layout.lastSeq(), 0);
    }

    @Override
    public boolean supportsRange() {
        return true;
    }

    @Override
    public InputStream getRange(String key, long offset, long length) throws IOException {
        BlobKeys.requireValidKey(key);
        BlobStreams.requireValidRange(offset, length);
        Layout layout = layout(key);
        if (length == 0 || layout.chunkBytes() == 0) {
            return InputStream.nullInputStream();
        }
        long startSeq = offset / layout.chunkBytes();
        if (startSeq > layout.lastSeq()) {
            return InputStream.nullInputStream();
        }
        int skip = (int) (offset % layout.chunkBytes());
        return BlobStreams.limit(new ChunkInputStream(key, (int) startSeq, layout.lastSeq(), skip), length);
    }

    @Override
    public void delete(String key) throws IOException {
        BlobKeys.requireValidKey(key);
        ensureSchema();
        try {
            deleteRows(key);
        } catch (SQLException e) {
            throw asIOException(e);
        }
    }

    @Override
    public Optional<BlobStat> stat(String key) throws IOException {
        BlobKeys.requireValidKey(key);
        ensureSchema();
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(SELECT_STAT)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 0 ? Optional.empty() : Optional.of(new BlobStat(key, rs.getLong(2)));
            }
        } catch (SQLException e) {
            throw asIOException(e);
        }
    }

    @Override
    public Stream<String> list(String prefix) throws IOException {
        BlobKeys.requireValidPrefix(prefix);
        ensureSchema();
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = connection();
            conn.setAutoCommit(false); // a server-side cursor needs a transaction
            ps = conn.prepareStatement(LIST);
            ps.setFetchSize(LIST_FETCH_SIZE);
            ps.setString(1, prefix + "%");
            rs = ps.executeQuery();
        } catch (SQLException e) {
            IOException failure = asIOException(e);
            closeAll(rs, ps, conn, failure);
            throw failure;
        }
        ResultSet results = rs;
        PreparedStatement statement = ps;
        Connection connection = conn;
        Iterator<String> keys = new Iterator<>() {
            private Boolean hasNext;

            @Override
            public boolean hasNext() {
                if (hasNext == null) {
                    try {
                        hasNext = results.next();
                    } catch (SQLException e) {
                        throw new UncheckedIOException(asIOException(e));
                    }
                }
                return hasNext;
            }

            @Override
            public String next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                hasNext = null;
                try {
                    return results.getString(1);
                } catch (SQLException e) {
                    throw new UncheckedIOException(asIOException(e));
                }
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(keys, Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(() -> closeAll(results, statement, connection, null));
    }

    private record Layout(int lastSeq, int chunkBytes) {
    }

    /** The last chunk's number and the size of the object's own chunks. */
    private Layout layout(String key) throws IOException {
        ensureSchema();
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(SELECT_LAYOUT)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int lastSeq = rs.getInt(1);
                if (rs.wasNull()) {
                    throw new NoSuchFileException("No blob with this key");
                }
                int firstChunkBytes = rs.getInt(2);
                if (rs.wasNull()) {
                    throw new IOException("Blob has no first chunk");
                }
                return new Layout(lastSeq, firstChunkBytes);
            }
        } catch (SQLException e) {
            throw asIOException(e);
        }
    }

    private byte[] fetchChunk(String key, int seq) throws IOException {
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(SELECT_CHUNK)) {
            if (ps.isWrapperFor(PGStatement.class)) {
                // Binary transfer from the first execution: in text mode the driver receives a
                // chunk hex-encoded, twice its size, and decodes it into a third copy.
                ps.unwrap(PGStatement.class).setPrepareThreshold(-1);
            }
            ps.setString(1, key);
            ps.setInt(2, seq);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IOException("Blob changed while it was being read: chunk " + seq + " is missing");
                }
                return rs.getBytes(1);
            }
        } catch (SQLException e) {
            throw asIOException(e);
        }
    }

    /** Reads chunks {@code firstSeq..lastSeq} one query at a time. */
    private final class ChunkInputStream extends InputStream {

        private final String key;
        private final int lastSeq;
        private int nextSeq;
        private int skipInFirst;
        private byte[] chunk = new byte[0];
        private int pos;
        private boolean closed;

        ChunkInputStream(String key, int firstSeq, int lastSeq, int skipInFirst) {
            this.key = key;
            this.nextSeq = firstSeq;
            this.lastSeq = lastSeq;
            this.skipInFirst = skipInFirst;
        }

        private boolean fill() throws IOException {
            if (closed) {
                throw new IOException("Stream closed");
            }
            while (pos >= chunk.length) {
                if (nextSeq > lastSeq) {
                    return false;
                }
                chunk = fetchChunk(key, nextSeq++);
                pos = Math.min(skipInFirst, chunk.length);
                skipInFirst = 0;
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            return fill() ? chunk[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int n = Math.min(len, chunk.length - pos);
            System.arraycopy(chunk, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public int available() {
            return chunk.length - pos;
        }

        @Override
        public void close() {
            closed = true;
            chunk = new byte[0];
        }
    }

    /**
     * Inserts one chunk on a connection borrowed for that insert alone, so a slow
     * uploader never holds a pooled connection while the next chunk arrives.
     */
    private int insertChunk(String sql, String key, int seq, byte[] data) throws SQLException {
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(true);
            ps.setString(1, key);
            ps.setInt(2, seq);
            ps.setBytes(3, data);
            return ps.executeUpdate();
        }
    }

    private void deleteRows(String key) throws SQLException {
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(DELETE)) {
            conn.setAutoCommit(true);
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    /**
     * Removes what a failed put wrote, on a fresh connection; never masks the
     * failure.
     */
    private void removePartial(String key, IOException failure) {
        try {
            deleteRows(key);
        } catch (SQLException | RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    private void ensureSchema() throws IOException {
        if (!schemaReady) {
            createSchema();
        }
    }

    private synchronized void createSchema() throws IOException {
        if (schemaReady) {
            return;
        }
        try (Connection conn = connection(); Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            try {
                stmt.execute(SCHEMA_LOCK); // replicas creating the table at once would race
                stmt.execute(CREATE_TABLE);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
            schemaReady = true;
        } catch (SQLException e) {
            throw asIOException(e);
        }
    }

    private Connection connection() throws SQLException {
        return dataSource.get().getConnection();
    }

    /** Closes each resource even if an earlier one fails to close. */
    private static void closeAll(ResultSet rs, Statement ps, Connection conn, Exception failure) {
        SQLException first = null;
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            first = e;
        }
        try {
            if (ps != null) {
                ps.close();
            }
        } catch (SQLException e) {
            first = first == null ? e : first;
        }
        if (conn != null) {
            try {
                if (!conn.getAutoCommit()) {
                    conn.rollback();
                }
            } catch (SQLException e) {
                first = first == null ? e : first;
            }
            try {
                conn.close();
            } catch (SQLException e) {
                first = first == null ? e : first;
            }
        }
        if (first == null) {
            return;
        }
        if (failure != null) {
            failure.addSuppressed(first);
        } else {
            throw new UncheckedIOException(asIOException(first));
        }
    }

    private static IOException asIOException(Exception e) {
        if (e instanceof IOException io) {
            return io;
        }
        if (e instanceof UncheckedIOException unchecked) {
            return unchecked.getCause();
        }
        return new IOException("pg-chunks blob backend failed: " + e.getMessage(), e);
    }
}
