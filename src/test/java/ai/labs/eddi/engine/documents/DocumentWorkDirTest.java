/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import ai.labs.eddi.engine.documents.DocumentWorkDir.BudgetExceeded;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DocumentWorkDir — per-instance directory and byte budget")
class DocumentWorkDirTest {

    private static final String CONVERSATION = "6650f1d2a3b4c5d6e7f80911";
    private static final String REF = "6650f1d2a3b4c5d6e7f80922";

    @TempDir
    Path base;

    private final List<DocumentWorkDir> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        opened.forEach(DocumentWorkDir::close);
    }

    private DocumentWorkDir workDir(String nodeId, String bootId, long maxBytes) {
        var workDir = new DocumentWorkDir(base, nodeId, bootId, maxBytes);
        opened.add(workDir);
        return workDir;
    }

    @Nested
    @DisplayName("the directory")
    class Directory {

        @Test
        @DisplayName("is a subdirectory of the base named after the node, never the base itself")
        void perInstance() {
            Path dir = workDir("node-a", "boot1", 1024).directory();

            assertEquals(base.resolve("node-node-a").toAbsolutePath().normalize(), dir);
            assertTrue(Files.isDirectory(dir));
        }

        @Test
        @DisplayName("a node id cannot name a path outside the base")
        void nodeIdIsSanitized() {
            Path dir = workDir("../../etc", "boot1", 1024).directory();

            assertEquals(base.toAbsolutePath().normalize(), dir.getParent());
        }

        @Test
        @DisplayName("startup empties this instance's own directory and leaves a peer's alone")
        void sweepsOnlyItsOwn() throws IOException {
            Path own = privateDir(base.resolve("node-a"));
            Path leftover = Files.writeString(own.resolve("eddi-blob-1.bin"), "left by the previous run");
            Path nested = Files.createDirectories(own.resolve("scratch"));
            Files.writeString(nested.resolve("PDFBox1.tmp"), "scratch");
            Path peer = privateDir(base.resolve("node-b"));
            Path peersFile = Files.writeString(peer.resolve("eddi-blob-2.bin"), "the peer's, in use");
            Path shared = Files.writeString(base.resolve("someone-elses.tmp"), "not EDDI's");

            workDir("a", "boot1", 1024).directory();

            assertFalse(Files.exists(leftover));
            assertFalse(Files.exists(nested));
            assertTrue(Files.exists(peersFile), "a peer's file survives");
            assertTrue(Files.exists(shared), "the shared root is never swept");
        }

        @Test
        @DisplayName("a second live process with the same node id gets its own directory and deletes nothing")
        void sameNodeIdTwice() throws IOException {
            var first = workDir("a", "boot1", 1024);
            Path firstDir = first.directory();
            Path inUse = Files.writeString(firstDir.resolve("eddi-blob-1.bin"), "being read");

            Path secondDir = workDir("a", "boot2", 1024).directory();

            assertNotEquals(firstDir, secondDir);
            assertEquals(base.resolve("node-a.boot-boot2").toAbsolutePath().normalize(), secondDir);
            assertTrue(Files.exists(inUse), "the first process's file survives");
        }

        @Test
        @DisplayName("another node whose id extends this one's is never taken for a per-boot directory")
        void nodeIdPrefixIsNotAPerBootDirectory() throws IOException {
            Path otherNode = privateDir(base.resolve("node-a-b"));
            Path othersFile = Files.writeString(otherNode.resolve("eddi-blob-1.bin"), "node a-b's, it is down");

            workDir("a", "boot1", 1024).directory();

            assertTrue(Files.exists(othersFile), "node a-b's directory is not node a's to clean");
        }

        @Test
        @DisplayName("a link inside the directory is deleted as a link; what it points to is untouched")
        void linksAreNotFollowed() throws IOException {
            Path own = privateDir(base.resolve("node-a"));
            Path outside = privateDir(base.resolve("outside"));
            Path precious = Files.writeString(outside.resolve("precious.txt"), "not EDDI's");
            Path link = own.resolve("link");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (IOException | UnsupportedOperationException noLinks) {
                assumeTrue(false, "this file system or user cannot create links");
            }

            workDir("a", "boot1", 1024).directory();

            assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "the link is gone");
            assertTrue(Files.exists(precious), "its target is not");
        }

        @Test
        @DisplayName("a directory others may write to is refused, not swept (POSIX)")
        void sharedDirectoryRefused() throws IOException {
            Path own = privateDir(base.resolve("node-a"));
            assumeTrue(Files.getFileAttributeView(own, PosixFileAttributeView.class) != null, "POSIX only");
            Files.setPosixFilePermissions(own, PosixFilePermissions.fromString("rwxrwxrwx"));
            Path planted = Files.writeString(own.resolve("planted"), "someone else's");

            var workDir = workDir("a", "boot1", 1024);
            assertThrows(IllegalStateException.class, workDir::directory);
            assertTrue(Files.exists(planted));
        }

        @Test
        @DisplayName("a per-boot directory whose owner is gone is removed; one whose owner lives is not")
        void abandonedPerBootDirectories() throws IOException {
            Path abandoned = privateDir(base.resolve("node-a.boot-oldboot"));
            Files.writeString(abandoned.resolve("eddi-blob-1.bin"), "left behind");
            var live = workDir("a", "boot1", 1024);
            live.directory();
            var twin = workDir("a", "boot2", 1024);
            Path twinDir = twin.directory();
            Path twinsFile = Files.writeString(twinDir.resolve("eddi-blob-2.bin"), "in use");
            live.close();

            workDir("a", "boot3", 1024).directory();

            assertFalse(Files.exists(abandoned), "nobody holds its lock");
            assertTrue(Files.exists(twinsFile), "its owner still holds the lock");
        }
    }

    @Nested
    @DisplayName("the byte budget")
    class Budget {

        @Test
        @DisplayName("a request larger than the whole budget is refused at once, naming the limit")
        void tooLargeEver() {
            var workDir = workDir("a", "boot1", 100);

            var refused = assertThrows(BudgetExceeded.class, () -> workDir.reserve(101, Duration.ofSeconds(2)));
            assertTrue(refused.getMessage().contains("eddi.attachments.large.work-dir-max-bytes"), refused.getMessage());
            assertEquals(0, workDir.usedBytes());
        }

        @Test
        @DisplayName("a request that does not fit now waits, and proceeds when space is released")
        void blocksThenProceeds() throws Exception {
            var workDir = workDir("a", "boot1", 100);
            var first = workDir.reserve(80, Duration.ZERO);
            var waiting = new CountDownLatch(1);

            CompletableFuture<DocumentWorkDir.Reservation> second = CompletableFuture.supplyAsync(() -> {
                waiting.countDown();
                try {
                    return workDir.reserve(50, Duration.ofSeconds(30));
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertFalse(second.isDone(), "80 + 50 > 100: it waits");

            first.close();
            try (var granted = second.get(10, TimeUnit.SECONDS)) {
                assertEquals(50, granted.bytes());
                assertEquals(50, workDir.usedBytes());
            }
            assertEquals(0, workDir.usedBytes());
        }

        @Test
        @DisplayName("a wait that ends first is refused")
        void timesOut() throws InterruptedException {
            var workDir = workDir("a", "boot1", 100);
            try (var held = workDir.reserve(80, Duration.ZERO)) {
                var refused = assertThrows(BudgetExceeded.class, () -> workDir.reserve(50, Duration.ofMillis(50)));
                assertTrue(refused.getMessage().contains("in use"), refused.getMessage());
                assertEquals(80, workDir.usedBytes());
            }
        }

        @Test
        @DisplayName("a waiting thread can be interrupted")
        void interruptible() throws Exception {
            var workDir = workDir("a", "boot1", 100);
            var held = workDir.reserve(100, Duration.ZERO);
            var outcome = new AtomicReference<Throwable>();
            Thread waiter = new Thread(() -> {
                try {
                    workDir.reserve(1, Duration.ofMinutes(5));
                } catch (Throwable t) {
                    outcome.set(t);
                }
            });
            waiter.start();
            Thread.sleep(100);
            waiter.interrupt();
            waiter.join(5_000);

            assertTrue(outcome.get() instanceof InterruptedException, String.valueOf(outcome.get()));
            held.close();
        }

        @Test
        @DisplayName("closing a reservation twice releases it once")
        void releaseOnce() throws InterruptedException {
            var workDir = workDir("a", "boot1", 100);
            var other = workDir.reserve(30, Duration.ZERO);
            var reservation = workDir.reserve(40, Duration.ZERO);
            reservation.close();
            reservation.close();

            assertEquals(30, workDir.usedBytes());
            other.close();
        }
    }

    @Nested
    @DisplayName("materialize")
    class Materialize {

        @Test
        @DisplayName("copies into this instance's directory under a reservation, and closing deletes and releases")
        void copiesAndCleansUp() throws Exception {
            var workDir = workDir("a", "boot1", 1_000);
            var store = storeWriting(500);

            Path file;
            try (var copy = workDir.materialize(store, REF, CONVERSATION, 600, Duration.ZERO)) {
                file = copy.path();
                assertEquals(workDir.directory(), file.getParent());
                assertEquals(600, workDir.usedBytes());
            }
            assertFalse(Files.exists(file));
            assertEquals(0, workDir.usedBytes());
        }

        @Test
        @DisplayName("bytes are reserved before the copy: no room, no copy")
        void reservesFirst() throws Exception {
            var workDir = workDir("a", "boot1", 1_000);
            var store = storeWriting(500);
            try (var held = workDir.reserve(900, Duration.ZERO)) {
                assertThrows(BudgetExceeded.class,
                        () -> workDir.materialize(store, REF, CONVERSATION, 600, Duration.ZERO));
            }
            try (var files = Files.list(workDir.directory())) {
                assertEquals(List.of(DocumentWorkDir.LOCK_FILE),
                        files.map(path -> path.getFileName().toString()).toList(), "nothing was copied");
            }
        }

        @Test
        @DisplayName("a copy larger than its reservation is deleted and refused")
        void largerThanReserved() throws Exception {
            var workDir = workDir("a", "boot1", 1_000);
            var store = storeWriting(700);

            assertThrows(BudgetExceeded.class, () -> workDir.materialize(store, REF, CONVERSATION, 600, Duration.ZERO));
            assertEquals(0, workDir.usedBytes());
            try (var files = Files.list(workDir.directory())) {
                assertEquals(1, files.count(), "only the lock file is left");
            }
        }

        @Test
        @DisplayName("a store failure releases the reservation")
        void storeFailure() throws Exception {
            var workDir = workDir("a", "boot1", 1_000);
            var store = mock(IAttachmentStore.class);
            when(store.materialize(eq(REF), eq(CONVERSATION), any()))
                    .thenThrow(new AttachmentStoreException("backend unreachable"));

            assertThrows(AttachmentStoreException.class,
                    () -> workDir.materialize(store, REF, CONVERSATION, 600, Duration.ZERO));
            assertEquals(0, workDir.usedBytes());
        }

        @Test
        @DisplayName("an Error from the store releases the reservation too")
        void storeError() throws Exception {
            var workDir = workDir("a", "boot1", 1_000);
            var store = mock(IAttachmentStore.class);
            when(store.materialize(eq(REF), eq(CONVERSATION), any())).thenThrow(new StackOverflowError("deep"));

            assertThrows(StackOverflowError.class,
                    () -> workDir.materialize(store, REF, CONVERSATION, 600, Duration.ZERO));
            assertEquals(0, workDir.usedBytes());
        }

        private IAttachmentStore storeWriting(int bytes) throws AttachmentStoreException {
            var store = mock(IAttachmentStore.class);
            when(store.materialize(eq(REF), eq(CONVERSATION), any())).thenAnswer(invocation -> {
                Path dir = invocation.getArgument(2);
                return Files.write(Files.createTempFile(dir, "eddi-blob-", ".bin"), new byte[bytes]);
            });
            return store;
        }
    }

    /**
     * A directory as this process would make it: owner-only where POSIX applies, so
     * a test does not depend on the umask it runs under.
     */
    private static Path privateDir(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
        return dir;
    }
}
