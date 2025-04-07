package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.*;
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

    @Override
    public void createZip(String sourceDirPath, String targetZipPath) throws IOException {
        File directoryToZip = new File(sourceDirPath);

        List<File> fileList = new LinkedList<>();
        getAllFiles(directoryToZip, fileList);
        writeZipFile(targetZipPath, directoryToZip, fileList);
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

    private static void writeZipFile(String targetZipFile, File directoryToZip, List<File> fileList) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(targetZipFile);
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
            // Ensure consistent path separators and protect against traversal in entry name creation itself
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
        String entryName = file.getCanonicalPath()
                .substring(directoryToZip.getCanonicalPath().length() + 1)
                .replace(File.separatorChar, '/'); // Use '/' for zip standard

        // Basic check for traversal sequences in the source file path relative to the source directory
        if (entryName.contains("../")) {
            throw new IOException("Malicious entry: " + entryName);
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
            while ((entry = zipIn.getNextEntry()) != null) {
                File destFile = new File(targetDir, entry.getName());
                String destFilePath = destFile.getCanonicalPath();

                // Ensure the resolved destination path starts with the target directory path
                if (!destFilePath.startsWith(targetDirPath + File.separator)) {
                    throw new IOException("Entry is outside of the target dir: " + entry.getName());
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
                    extractFile(zipIn, destFile);
                }
                zipIn.closeEntry();
            }
        }
    }

    private void extractFile(ZipInputStream zipIn, File destFile) throws IOException {
        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(destFile))) {
            byte[] bytesIn = new byte[BUFFER_SIZE];
            int read;
            while ((read = zipIn.read(bytesIn)) != -1) {
                bos.write(bytesIn, 0, read);
            }
        }
    }
}
