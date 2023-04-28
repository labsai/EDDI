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
@SuppressWarnings("ResultOfMethodCallIgnored")
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
        FileOutputStream fos = new FileOutputStream(targetZipFile);
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos));

        for (File file : fileList) {
            if (!file.isDirectory()) { // we only zip files, not directories
                addToZip(directoryToZip, file, zos);
            }
        }

        zos.close();
        fos.close();
    }

    private static void addToZip(File directoryToZip, File file, ZipOutputStream zos) throws IOException {
        FileInputStream fis = new FileInputStream(file);

        String zipFilePath = file.getCanonicalPath().
                substring(directoryToZip.getCanonicalPath().length() + 1).replace('\\', '/');
        ZipEntry zipEntry = new ZipEntry(zipFilePath);
        zos.putNextEntry(zipEntry);

        byte[] bytes = new byte[1024];
        int length;
        while ((length = fis.read(bytes)) >= 0) {
            zos.write(bytes, 0, length);
        }

        zos.closeEntry();
        fis.close();
    }

    @Override
    public void unzip(InputStream zipFile, File targetDir) throws IOException {
        if (!targetDir.exists()) {
            targetDir.mkdir();
        }
        ZipInputStream zipIn = new ZipInputStream(zipFile);

        ZipEntry entry = zipIn.getNextEntry();
        // iterates over entries in the zip file
        while (entry != null) {
            String filePath = targetDir.getPath() + File.separator + entry.getName();
            if (!entry.isDirectory()) {
                // if the entry is a file, extracts it
                new File(filePath).getParentFile().mkdirs();
                extractFile(zipIn, filePath);
            } else {
                // if the entry is a directory, make the directory
                File dir = new File(filePath);
                dir.mkdirs();
            }
            zipIn.closeEntry();
            entry = zipIn.getNextEntry();
        }
        zipIn.close();
    }

    private void extractFile(ZipInputStream zipIn, String filePath) throws IOException {
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
        byte[] bytesIn = new byte[BUFFER_SIZE];
        int read;
        while ((read = zipIn.read(bytesIn)) != -1) {
            bos.write(bytesIn, 0, read);
        }
        bos.close();
    }
}
