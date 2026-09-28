/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;

import jakarta.enterprise.context.ApplicationScoped;

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

    /**
     * Decompression-bomb ceilings for {@link #unzip}. An agent-config ZIP is a
     * modest set of JSON documents, so these are generous headroom rather than
     * expected sizes; a well under 25 MB upload that inflates past them is a bomb,
     * not a backup. Counted as bytes are actually read, never trusting
     * {@link ZipEntry#getSize()} (which the archive author controls and can lie
     * about or leave as -1).
     */
    static final int MAX_ENTRIES = 10_000;
    static final long MAX_ENTRY_INFLATED_BYTES = 100L * 1024 * 1024;
    static final long MAX_TOTAL_INFLATED_BYTES = 500L * 1024 * 1024;

    private final int maxEntries;
    private final long maxEntryInflatedBytes;
    private final long maxTotalInflatedBytes;

    public ZipArchive() {
        this(MAX_ENTRIES, MAX_ENTRY_INFLATED_BYTES, MAX_TOTAL_INFLATED_BYTES);
    }

    /**
     * Test seam: lets a test drive the bomb ceilings without inflating gigabytes.
     */
    ZipArchive(int maxEntries, long maxEntryInflatedBytes, long maxTotalInflatedBytes) {
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
                    throw new IOException("Zip archive has too many entries (limit " + maxEntries + ")");
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
                    throw new IOException("Zip entry '" + entryName + "' exceeds the maximum inflated size of "
                            + maxEntryInflatedBytes + " bytes");
                }
                if (totalInflated[0] > maxTotalInflatedBytes) {
                    throw new IOException("Zip archive exceeds the maximum total inflated size of "
                            + maxTotalInflatedBytes + " bytes");
                }
                bos.write(bytesIn, 0, read);
            }
        }
    }
}
