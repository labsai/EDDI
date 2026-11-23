/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Where large documents are copied to disk while they are read, and how much of
 * the disk that may take.
 *
 * <p>
 * <b>One subdirectory per instance.</b> Several EDDI nodes may share a temp
 * volume, so this never works in, or sweeps, the shared root directly. Each node
 * uses {@code <work-dir>/node-<nodeId>} — the node id is stable across restarts
 * ({@link NodeIdentity}), so a restarted node finds and deletes what its previous
 * incarnation left — and holds an exclusive lock on a file in it for as long as
 * it runs. A second live JVM with the same node id (two dev instances on one
 * host) finds the lock taken and uses {@code node-<nodeId>-<bootId>} instead, and
 * sweeps nothing of the first's. At startup a node also removes such per-boot
 * directories of its own node id whose lock nobody holds any more: their owner
 * is gone. A directory whose lock is held is never touched.
 *
 * <p>
 * <b>A byte budget.</b> {@link #reserve} blocks until the bytes are free, up to
 * a wait the caller chooses, and refuses at once what could never fit. A
 * reservation is released when it is closed; {@link #materialize} ties one to
 * the file it copies, which is deleted on {@link WorkFile#close()}.
 *
 * <p>
 * Directories are created owner-only ({@code 0700}) and files owner-only
 * ({@code 0600}) where the file system has POSIX permissions.
 */
@ApplicationScoped
public class DocumentWorkDir {

    private static final Logger LOGGER = Logger.getLogger(DocumentWorkDir.class);

    /** The file each instance directory's owner holds locked while it runs. */
    static final String LOCK_FILE = ".lock";

    static final String DIRECTORY_PREFIX = "node-";

    /** The default {@code eddi.attachments.large.work-dir-max-bytes}: 4 GiB. */
    public static final long DEFAULT_MAX_BYTES = 4L * 1024 * 1024 * 1024;

    private final Path base;
    private final String nodeId;
    private final String bootId;
    private final long maxBytes;

    private final ReentrantLock budgetLock = new ReentrantLock();
    private final Condition released = budgetLock.newCondition();
    private long usedBytes;

    private Path directory;
    private FileChannel lockChannel;
    private FileLock lock;

    @Inject
    public DocumentWorkDir(NodeIdentity node,
            @ConfigProperty(name = "eddi.attachments.large.work-dir") Optional<String> configuredBase,
            @ConfigProperty(name = "eddi.attachments.large.work-dir-max-bytes", defaultValue = "4294967296") long maxBytes) {
        this(configuredBase.filter(value -> !value.isBlank()).map(Path::of).orElseGet(DocumentWorkDir::defaultBase),
                node.nodeId(), node.bootId(), maxBytes);
    }

    /**
     * @param base
     *            the shared root; this instance works only in a subdirectory of it
     * @param nodeId
     *            stable across restarts, characters safe in a file name
     * @param bootId
     *            new on every start
     */
    public DocumentWorkDir(Path base, String nodeId, String bootId, long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("eddi.attachments.large.work-dir-max-bytes must be positive");
        }
        this.base = base.toAbsolutePath().normalize();
        this.nodeId = fileNameToken(nodeId);
        this.bootId = fileNameToken(bootId);
        this.maxBytes = maxBytes;
    }

    /** Only characters safe in a file name on every platform; never a path separator. */
    static String fileNameToken(String raw) {
        String token = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_-]", "_");
        return token.isEmpty() ? "node" : token;
    }

    static Path defaultBase() {
        return Path.of(System.getProperty("java.io.tmpdir"), "eddi-documents");
    }

    void onStart(@Observes StartupEvent event) {
        try {
            directory();
        } catch (RuntimeException e) {
            // Nothing reads large documents yet unless a later release switches it on;
            // that code fails with the reason when it asks for the directory.
            LOGGER.warnf("The document work directory under %s is not usable: %s", base, e.getMessage());
        }
    }

    /**
     * This instance's directory, created, locked and swept on first use.
     *
     * @throws IllegalStateException
     *             when it cannot be created or locked
     */
    public synchronized Path directory() {
        if (directory == null) {
            try {
                start();
            } catch (IOException e) {
                throw new IllegalStateException("The document work directory under " + base
                        + " cannot be used: " + e.getMessage(), e);
            }
        }
        return directory;
    }

    private void start() throws IOException {
        createOwnerOnly(base);
        Path stable = base.resolve(DIRECTORY_PREFIX + nodeId);
        createOwnerOnly(stable);
        if (tryLock(stable)) {
            directory = stable;
            sweep(stable);
            removeAbandonedBootDirectories();
            return;
        }
        Path perBoot = base.resolve(DIRECTORY_PREFIX + nodeId + "-" + bootId);
        createOwnerOnly(perBoot);
        if (!tryLock(perBoot)) {
            throw new IOException("another process holds both " + stable + " and " + perBoot);
        }
        LOGGER.warnf("Another live process uses the document work directory %s (two instances with node id '%s'?); "
                + "this one uses %s. Set eddi.cluster.node-id per instance.", stable, nodeId, perBoot);
        directory = perBoot;
    }

    /** Takes this instance's lock in {@code dir}; false when someone holds it. */
    private boolean tryLock(Path dir) throws IOException {
        FileChannel channel = FileChannel.open(dir.resolve(LOCK_FILE), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock taken = tryLock(channel);
        if (taken == null) {
            channel.close();
            return false;
        }
        lockChannel = channel;
        lock = taken;
        return true;
    }

    private static FileLock tryLock(FileChannel channel) {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException | IOException heldElsewhere) {
            // Held in this JVM, or (Windows) by another process.
            return null;
        }
    }

    /**
     * Removes the per-boot directories of this node id whose owner is gone: those
     * whose lock can be taken. A held lock means a live owner; its directory is
     * left alone.
     */
    private void removeAbandonedBootDirectories() {
        String prefix = DIRECTORY_PREFIX + nodeId + "-";
        try (DirectoryStream<Path> siblings = Files.newDirectoryStream(base, prefix + "*")) {
            for (Path sibling : siblings) {
                if (!Files.isDirectory(sibling, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try (FileChannel channel = FileChannel.open(sibling.resolve(LOCK_FILE), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE)) {
                    FileLock abandoned = tryLock(channel);
                    if (abandoned == null) {
                        continue;
                    }
                    sweep(sibling);
                    abandoned.release();
                }
                deleteQuietly(sibling.resolve(LOCK_FILE));
                deleteQuietly(sibling);
            }
        } catch (IOException e) {
            LOGGER.warnf("Could not clean old document work directories under %s: %s", base, e.getMessage());
        }
    }

    /**
     * Deletes everything inside {@code dir} except its lock file. Links are deleted
     * as links, never followed.
     */
    static void sweep(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!(file.getParent().equals(dir) && file.getFileName().toString().equals(LOCK_FILE))) {
                    deleteQuietly(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                deleteQuietly(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path visited, IOException e) {
                if (!visited.equals(dir)) {
                    deleteQuietly(visited);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warnf("Could not delete %s: %s", path, e.getMessage());
        }
    }

    private static void createOwnerOnly(Path dir) throws IOException {
        if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException noPosix) {
            Files.createDirectories(dir);
        }
    }

    /** The most this directory may hold. */
    public long maxBytes() {
        return maxBytes;
    }

    /** What is reserved right now. */
    public long usedBytes() {
        budgetLock.lock();
        try {
            return usedBytes;
        } finally {
            budgetLock.unlock();
        }
    }

    /**
     * Reserves {@code bytes} of the budget, waiting up to {@code wait} for others
     * to release theirs.
     *
     * @throws BudgetExceeded
     *             at once when {@code bytes} exceeds the whole budget, or when the
     *             wait ends first
     * @throws InterruptedException
     *             when the thread is interrupted while it waits
     */
    public Reservation reserve(long bytes, Duration wait) throws InterruptedException {
        if (bytes <= 0) {
            throw new IllegalArgumentException("bytes must be positive");
        }
        if (bytes > maxBytes) {
            throw new BudgetExceeded("This file needs " + megabytes(bytes) + " MB of working disk space, more than "
                    + "the " + megabytes(maxBytes) + " MB this server allows (eddi.attachments.large.work-dir-max-bytes).");
        }
        long nanos = wait == null || wait.isNegative() ? 0 : wait.toNanos();
        budgetLock.lockInterruptibly();
        try {
            while (usedBytes + bytes > maxBytes) {
                if (nanos <= 0) {
                    throw new BudgetExceeded("The working disk space for large documents is in use (" + megabytes(usedBytes)
                            + " of " + megabytes(maxBytes) + " MB); try again later.");
                }
                nanos = released.awaitNanos(nanos);
            }
            usedBytes += bytes;
            return new Reservation(bytes);
        } finally {
            budgetLock.unlock();
        }
    }

    private void release(long bytes) {
        budgetLock.lock();
        try {
            usedBytes -= bytes;
            released.signalAll();
        } finally {
            budgetLock.unlock();
        }
    }

    /**
     * Copies a stored file into this directory under a reservation of
     * {@code reserveBytes} — the file's size, plus any scratch space its reader
     * will use here. Bytes are reserved before the first is copied.
     *
     * @throws BudgetExceeded
     *             when the bytes cannot be reserved within {@code wait}, or the
     *             copy turns out larger than the reservation (it is deleted)
     */
    public WorkFile materialize(IAttachmentStore store, String storageRef, String requestingConversationId,
                                long reserveBytes, Duration wait)
            throws AttachmentStoreException, InterruptedException {
        Path dir = directory();
        Reservation reservation = reserve(reserveBytes, wait);
        Path file = null;
        try {
            file = store.materialize(storageRef, requestingConversationId, dir);
            long size = Files.size(file);
            if (size > reserveBytes) {
                throw new BudgetExceeded("The file is larger (" + size + " bytes) than the space reserved for it ("
                        + reserveBytes + " bytes).");
            }
            return new WorkFile(file, reservation);
        } catch (IOException e) {
            cleanUp(file, reservation);
            throw new AttachmentStoreException("Failed to measure the copied attachment", e);
        } catch (RuntimeException | AttachmentStoreException e) {
            cleanUp(file, reservation);
            throw e;
        }
    }

    private static void cleanUp(Path file, Reservation reservation) {
        if (file != null) {
            deleteQuietly(file);
        }
        reservation.close();
    }

    @PreDestroy
    synchronized void close() {
        try {
            if (lock != null) {
                lock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException e) {
            LOGGER.debugf("Releasing the work directory lock: %s", e.getMessage());
        }
        lock = null;
        lockChannel = null;
        directory = null;
    }

    private static long megabytes(long bytes) {
        return Math.max(1, bytes / (1024 * 1024));
    }

    /** Bytes of the budget held until closed. Closing twice releases once. */
    public final class Reservation implements AutoCloseable {
        private final long bytes;
        private boolean closed;

        private Reservation(long bytes) {
            this.bytes = bytes;
        }

        public long bytes() {
            return bytes;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                release(bytes);
            }
        }
    }

    /** A copied file and its reservation; closing deletes the file and releases both. */
    public static final class WorkFile implements AutoCloseable {
        private final Path path;
        private final Reservation reservation;

        private WorkFile(Path path, Reservation reservation) {
            this.path = path;
            this.reservation = reservation;
        }

        public Path path() {
            return path;
        }

        @Override
        public void close() {
            try {
                deleteQuietly(path);
            } finally {
                reservation.close();
            }
        }
    }

    /** The work directory cannot take this now, or ever; the message says which. */
    public static final class BudgetExceeded extends RuntimeException {
        public BudgetExceeded(String message) {
            super(message);
        }
    }
}
