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
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
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
        // The raw archive is copied aside as it streams past, so its central directory
        // can be checked once the entries are out (see requireCompleteArchive).
        Path rawCopy = Files.createTempFile(targetDir.getCanonicalFile().getParentFile().toPath(), "upload-", ".zip");
        try (OutputStream rawOut = new BufferedOutputStream(Files.newOutputStream(rawCopy));
                InputStream teed = new TeeInputStream(zipFile, rawOut, maxRawArchiveBytes())) {
            extractEntries(teed, targetDir, targetDirPath);
            // Whatever ZipInputStream did not consume — the central directory and the
            // end record — still has to reach the copy.
            teed.transferTo(OutputStream.nullOutputStream());
            rawOut.flush();
            requireCompleteArchive(rawCopy);
        } finally {
            Files.deleteIfExists(rawCopy);
        }
    }

    /**
     * {@link ZipInputStream} reads local entry headers only, and treats end of
     * input where the next header should be as a normal end. An upload cut off
     * after its last complete entry — before the central directory — therefore
     * unpacked "successfully" with entries missing, and the importer worked from an
     * incomplete agent. {@link ZipFile} reads the central directory and the end
     * record, so opening the copy is the completeness check.
     */
    private static void requireCompleteArchive(Path rawCopy) throws IOException {
        try {
            // Opening it is the check: ZipFile refuses an archive without a readable
            // central directory and end record.
            new ZipFile(rawCopy.toFile()).close();
        } catch (ZipException e) {
            throw new MalformedArchiveException("Zip archive is corrupt or truncated: " + e.getMessage());
        }
    }

    private void extractEntries(InputStream archive, File targetDir, String targetDirPath) throws IOException {
        try (ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(archive) {
            @Override
            public void close() {
                // The tee owns the underlying stream: it still has to read what
                // ZipInputStream leaves behind.
            }
        })) {
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
                String destFilePath;
                try {
                    destFilePath = destFile.getCanonicalPath();
                } catch (IOException e) {
                    // A name the file system cannot even resolve (a NUL byte, a reserved
                    // device name on Windows) is the archive's fault, not this server's.
                    throw new MalformedArchiveException("Zip entry has a name that is not a valid file path");
                }

                // Ensure the resolved destination path starts with the target directory path
                if (!destFilePath.startsWith(targetDirPath + File.separator)) {
                    throw new MalformedArchiveException("Zip entry escapes target directory");
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
        } catch (ZipException | EOFException e) {
            // Corrupt or truncated archive data (bad compression stream, CRC
            // mismatch, cut-off upload). Never quotes the entry contents.
            throw new MalformedArchiveException("Zip archive is corrupt or truncated: " + e.getMessage());
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

    /**
     * Allowance per entry, on top of the inflated-bytes limit, for what an archive
     * holds besides content: local headers, data descriptors, the central directory
     * record and the entry name, twice. Generous — a real header set is well under
     * 1 KiB.
     */
    static final long RAW_OVERHEAD_PER_ENTRY = 1024;

    /**
     * The most raw (compressed) bytes one archive may carry. Stored entries make an
     * archive about as large as its content, so the content limit plus the
     * per-entry overhead bounds every legitimate one. It is what bounds the raw
     * copy, and the drain that follows the entries: without it, a stream that does
     * not end — a caller that is not behind the HTTP body limit, a sync source —
     * would be copied to disk for as long as it kept sending after its last entry.
     */
    long maxRawArchiveBytes() {
        return maxTotalInflatedBytes + (long) maxEntries * RAW_OVERHEAD_PER_ENTRY;
    }

    /**
     * Copies every byte read through it to a side stream, refusing to read past a
     * ceiling.
     */
    private static final class TeeInputStream extends FilterInputStream {
        private final OutputStream copy;
        private final long maxBytes;
        private long copied;

        TeeInputStream(InputStream in, OutputStream copy, long maxBytes) {
            super(in);
            this.copy = copy;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                count(1);
                copy.write(b);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                count(n);
                copy.write(buffer, offset, n);
            }
            return n;
        }

        private void count(int n) throws ZipLimitExceededException {
            copied += n;
            if (copied > maxBytes) {
                throw new ZipLimitExceededException("Zip archive is larger than " + maxBytes + " bytes (" + MAX_TOTAL_BYTES_PROPERTY
                        + " plus " + RAW_OVERHEAD_PER_ENTRY + " bytes per allowed entry, " + MAX_ENTRIES_PROPERTY + ")");
            }
        }

        @Override
        public long skip(long n) throws IOException {
            // Skipped bytes must reach the copy too.
            byte[] discard = new byte[(int) Math.min(n, BUFFER_SIZE)];
            long skipped = 0;
            while (skipped < n) {
                int r = read(discard, 0, (int) Math.min(discard.length, n - skipped));
                if (r < 0) {
                    break;
                }
                skipped += r;
            }
            return skipped;
        }
    }

    /**
     * An archive this deployment refuses because of its shape — an entry that would
     * land outside the extraction directory (zip-slip), a name that is not a path,
     * corrupt or truncated data — so a caller can answer 400 with the reason rather
     * than 500. The refusal itself always worked; only the status was wrong.
     */
    public static class MalformedArchiveException extends IOException {
        public MalformedArchiveException(String message) {
            super(message);
        }
    }
}
