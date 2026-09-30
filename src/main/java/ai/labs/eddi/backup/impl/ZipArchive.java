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
import java.nio.file.Files;
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

    /**
     * Decompression-bomb ceilings for {@link #unzip}, overridable per deployment
     * through the three properties above. An agent-config ZIP is a few hundred
     * small JSON documents, so these sit far above anything an export writes and
     * far below what a bomb needs: without them a 2 MB upload that inflates to
     * gigabytes filled the disk and inodes under {@code tmp/import}. The per-entry
     * ceiling also bounds the heap, because the importer reads each file whole.
     * Counted as bytes are actually read, never trusting {@link ZipEntry#getSize()}
     * (which the archive author controls and can lie about or leave as -1).
     */
    static final int MAX_ENTRIES = 10_000;
    static final long MAX_ENTRY_INFLATED_BYTES = 32L * 1024 * 1024;
    static final long MAX_TOTAL_INFLATED_BYTES = 256L * 1024 * 1024;

    private final int maxEntries;
    private final long maxEntryInflatedBytes;
    private final long maxTotalInflatedBytes;

    /** The shipped limits — for callers outside CDI. */
    public ZipArchive() {
        this(MAX_ENTRIES, MAX_ENTRY_INFLATED_BYTES, MAX_TOTAL_INFLATED_BYTES);
    }

    /**
     * The configured limits; also the test seam that drives the ceilings without
     * inflating gigabytes.
     */
    @Inject
    public ZipArchive(@ConfigProperty(name = MAX_ENTRIES_PROPERTY, defaultValue = "10000") int maxEntries,
            @ConfigProperty(name = MAX_ENTRY_BYTES_PROPERTY, defaultValue = "33554432") long maxEntryInflatedBytes,
            @ConfigProperty(name = MAX_TOTAL_BYTES_PROPERTY, defaultValue = "268435456") long maxTotalInflatedBytes) {
        this.maxEntries = maxEntries;
        this.maxEntryInflatedBytes = maxEntryInflatedBytes;
        this.maxTotalInflatedBytes = maxTotalInflatedBytes;
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
        } catch (IOException | RuntimeException e) {
            // Do not leave a truncated archive behind under the target name: it
            // would sit in the download directory until the retention sweep and
            // read as a valid-looking but incomplete export.
            try {
                Files.deleteIfExists(targetPath);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
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
        try (ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(zipFile))) {
            ZipEntry entry;
            int entryCount = 0;
            // A running total across all entries, so many small entries cannot add up
            // to a bomb any more than one huge entry can.
            long[] totalInflated = {0L};
            while ((entry = zipIn.getNextEntry()) != null) {
                if (++entryCount > maxEntries) {
                    throw new ZipLimitExceededException("Zip archive has too many entries (limit " + maxEntries + ", "
                            + MAX_ENTRIES_PROPERTY + ")");
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
                    extractFile(zipIn, destFile, entry.getName(), totalInflated);
                }
                zipIn.closeEntry();
            }
        }
    }

    private void extractFile(ZipInputStream zipIn, File destFile, String entryName, long[] totalInflated) throws IOException {
        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(destFile))) {
            byte[] bytesIn = new byte[BUFFER_SIZE];
            long entryInflated = 0;
            int read;
            while ((read = zipIn.read(bytesIn)) != -1) {
                entryInflated += read;
                totalInflated[0] += read;
                // Measured as it inflates, so a highly-compressed entry is aborted
                // mid-stream rather than after 25 GB has hit the disk.
                if (entryInflated > maxEntryInflatedBytes) {
                    throw new ZipLimitExceededException("Zip entry '" + entryName + "' exceeds the maximum inflated size of "
                            + maxEntryInflatedBytes + " bytes (" + MAX_ENTRY_BYTES_PROPERTY + ")");
                }
                if (totalInflated[0] > maxTotalInflatedBytes) {
                    throw new ZipLimitExceededException("Zip archive exceeds the maximum total inflated size of "
                            + maxTotalInflatedBytes + " bytes (" + MAX_TOTAL_BYTES_PROPERTY + ")");
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
