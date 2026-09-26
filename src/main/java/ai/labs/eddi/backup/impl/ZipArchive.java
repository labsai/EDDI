/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.*;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * @author ginccc
 */
@ApplicationScoped
public class ZipArchive implements IZipArchive {
    private static final int BUFFER_SIZE = 4096;

    static final String MAX_ENTRIES_PROPERTY = "eddi.backup.import.max-entries";
    static final String MAX_ENTRY_BYTES_PROPERTY = "eddi.backup.import.max-entry-bytes";
    static final String MAX_TOTAL_BYTES_PROPERTY = "eddi.backup.import.max-uncompressed-bytes";

    static final int DEFAULT_MAX_ENTRIES = 10_000;
    static final long DEFAULT_MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    static final long DEFAULT_MAX_TOTAL_BYTES = 256L * 1024 * 1024;

    /**
     * Unpacking limits. An agent archive is a few hundred small JSON documents, so
     * these sit far above anything an export writes and far below what a
     * decompression bomb needs: without them a 2 MB upload that inflates to
     * gigabytes filled the disk and inodes under {@code tmp/import}, and the import
     * then read each file whole into the heap. The sizes are counted from the bytes
     * actually inflated, never from the entry header, which the archive author
     * controls.
     */
    private final int maxEntries;
    private final long maxEntryBytes;
    private final long maxTotalBytes;

    /** The shipped limits — for callers outside CDI. */
    public ZipArchive() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_ENTRY_BYTES, DEFAULT_MAX_TOTAL_BYTES);
    }

    @Inject
    public ZipArchive(@ConfigProperty(name = MAX_ENTRIES_PROPERTY, defaultValue = "10000") int maxEntries,
            @ConfigProperty(name = MAX_ENTRY_BYTES_PROPERTY, defaultValue = "67108864") long maxEntryBytes,
            @ConfigProperty(name = MAX_TOTAL_BYTES_PROPERTY, defaultValue = "268435456") long maxTotalBytes) {
        this.maxEntries = maxEntries;
        this.maxEntryBytes = maxEntryBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    @Override
    public void createZip(String sourceDirPath, String targetZipPath, Path allowedBaseDir) throws IOException {
        File directoryToZip = new File(sourceDirPath);

        List<File> fileList = new LinkedList<>();
        getAllFiles(directoryToZip, fileList);
        writeZipFile(targetZipPath, directoryToZip, fileList, allowedBaseDir);
    }

    private static void getAllFiles(File dir, List<File> fileList) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                fileList.add(file);
                if (file.isDirectory()) {
                    getAllFiles(file, fileList);
                }
            }
        }
    }

    private static void writeZipFile(String targetZipFile, File directoryToZip, List<File> fileList, Path allowedBaseDir) throws IOException {
        // Validate the target path stays within the allowed base directory (CodeQL
        // java/path-injection)
        Path targetPath = Path.of(targetZipFile).normalize().toAbsolutePath();
        Path baseDir = allowedBaseDir.normalize().toAbsolutePath();
        if (!targetPath.startsWith(baseDir)) {
            throw new IOException("Target zip path escapes allowed base directory");
        }

        try (FileOutputStream fos = new FileOutputStream(targetPath.toFile());
                ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {

            for (File file : fileList) {
                if (!file.isDirectory()) {
                    addToZip(directoryToZip, file, zos);
                }
            }
        }
    }

    private static void addToZip(File directoryToZip, File file, ZipOutputStream zos) throws IOException {
        // Use try-with-resources for automatic stream closing
        try (FileInputStream fis = new FileInputStream(file)) {
            // Ensure consistent path separators and protect against traversal in entry name
            // creation itself
            var zipEntry = getZipEntry(directoryToZip, file);
            zos.putNextEntry(zipEntry);

            byte[] bytes = new byte[BUFFER_SIZE];
            int length;
            while ((length = fis.read(bytes)) >= 0) {
                zos.write(bytes, 0, length);
            }
            zos.closeEntry();
        }
    }

    private static ZipEntry getZipEntry(File directoryToZip, File file) throws IOException {
        // Use '/' for zip standard
        String entryName = file.getCanonicalPath().substring(directoryToZip.getCanonicalPath().length() + 1).replace(File.separatorChar, '/');
        // Basic check for traversal sequences in the source file path relative to the
        // source directory
        if (entryName.contains("../")) {
            throw new IOException("Zip entry contains directory traversal sequence");
        }

        return new ZipEntry(entryName);
    }

    /**
     * @throws ZipLimitExceededException
     *             when the archive holds more entries, or inflates to more bytes,
     *             than this deployment accepts. Whatever was written before the
     *             limit tripped is left in {@code targetDir}; every caller unpacks
     *             into a scratch directory it removes in a {@code finally}.
     */
    @Override
    public void unzip(InputStream zipFile, File targetDir) throws IOException {
        if (!targetDir.exists()) {
            if (!targetDir.mkdirs()) {
                throw new IOException("Could not create target directory: " + targetDir);
            }
        }

        String targetDirPath = targetDir.getCanonicalPath();
        int entries = 0;
        long[] totalBytes = {0};
        try (ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (++entries > maxEntries) {
                    throw new ZipLimitExceededException("The archive holds more than " + maxEntries
                            + " entries, more than this instance imports (" + MAX_ENTRIES_PROPERTY + ").");
                }
                File destFile = new File(targetDir, entry.getName());
                String destFilePath = destFile.getCanonicalPath();

                // Ensure the resolved destination path starts with the target directory path
                if (!destFilePath.startsWith(targetDirPath + File.separator)) {
                    throw new IOException("Zip entry escapes target directory");
                }

                if (entry.isDirectory()) {
                    if (!destFile.mkdirs() && !destFile.isDirectory()) {
                        throw new IOException("Could not create directory: " + destFilePath);
                    }
                } else {
                    File parentDir = destFile.getParentFile();
                    if (!parentDir.mkdirs() && !parentDir.isDirectory()) {
                        throw new IOException("Could not create parent directories for: " + destFilePath);
                    }
                    extractFile(zipIn, destFile, totalBytes);
                }
                zipIn.closeEntry();
            }
        }
    }

    private void extractFile(ZipInputStream zipIn, File destFile, long[] totalBytes) throws IOException {
        long entryBytes = 0;
        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(destFile))) {
            byte[] bytesIn = new byte[BUFFER_SIZE];
            int read;
            while ((read = zipIn.read(bytesIn)) != -1) {
                entryBytes += read;
                totalBytes[0] += read;
                if (entryBytes > maxEntryBytes) {
                    throw new ZipLimitExceededException("An archive entry inflates to more than " + maxEntryBytes
                            + " bytes, more than this instance imports (" + MAX_ENTRY_BYTES_PROPERTY + ").");
                }
                if (totalBytes[0] > maxTotalBytes) {
                    throw new ZipLimitExceededException("The archive inflates to more than " + maxTotalBytes
                            + " bytes, more than this instance imports (" + MAX_TOTAL_BYTES_PROPERTY + ").");
                }
                bos.write(bytesIn, 0, read);
            }
        }
    }

    /**
     * An archive this deployment refuses to unpack because of its size, not its
     * shape — so a caller can answer 413 rather than 500.
     */
    public static class ZipLimitExceededException extends IOException {
        public ZipLimitExceededException(String message) {
            super(message);
        }
    }
}
