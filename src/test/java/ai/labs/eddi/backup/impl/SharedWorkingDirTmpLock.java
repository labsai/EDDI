/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Serialises {@link SharedWorkingDirTmp} classes across surefire forks with an
 * exclusive lock on one file under {@code target/}. The lock belongs to the
 * process, so it excludes the other forks; within a fork the classes already
 * run one after another.
 * <p>
 * Taken once per top-level class: a {@code @Nested} class inherits the
 * extension and finds the lock already held by its enclosing class's context.
 */
final class SharedWorkingDirTmpLock implements BeforeAllCallback, AfterAllCallback {

    private static final Path LOCK_FILE = Path.of("target", "shared-working-dir-tmp.lock");
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(SharedWorkingDirTmpLock.class);
    private static final String KEY = "lock";

    @Override
    public void beforeAll(ExtensionContext context) throws IOException {
        // get() searches the enclosing contexts too, so a nested class is covered.
        if (context.getStore(NAMESPACE).get(KEY) != null) {
            return;
        }
        Files.createDirectories(LOCK_FILE.getParent());
        FileChannel channel = FileChannel.open(LOCK_FILE, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            context.getStore(NAMESPACE).put(KEY, new Held(channel, channel.lock()));
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws IOException {
        // remove() only looks at this context, so a nested class releases nothing.
        Held held = context.getStore(NAMESPACE).remove(KEY, Held.class);
        if (held != null) {
            held.release();
        }
    }

    private record Held(FileChannel channel, FileLock lock) {
        void release() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
