package ai.labs.utilities;

import java.io.*;
import java.util.Collections;
import java.util.List;

public final class FileUtilities {
    private static final String lineSeparator = System.getProperty("line.separator");

    private FileUtilities() {
        //utility class
    }

    public static String readTextFromFile(File file) throws IOException {
        BufferedReader rd = null;
        StringBuilder ret = new StringBuilder();
        try {
            rd = new BufferedReader(new FileReader(file));
            while (rd.ready()) {
                ret.append(rd.readLine());
                ret.append(lineSeparator);
            }

            return ret.toString();

        } finally {
            if (rd != null) {
                try {
                    rd.close();
                } catch (IOException e) {
                    //do nothing
                }
            }
        }
    }

    public static void writeTextToFile(File file, String content) throws IOException {

        BufferedWriter bufferedWriter = null;
        try {
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                if (!file.createNewFile()) {
                    throw new IOException("Cannot create target file.");
                }
            }

            FileWriter fileWriter = new FileWriter(file.getAbsoluteFile());
            bufferedWriter = new BufferedWriter(fileWriter);
            bufferedWriter.write(content);
        } finally {
            if (bufferedWriter != null) {
                try {
                    bufferedWriter.close();
                } catch (IOException e) {
                    //do nothing
                }
            }
        }
    }

    public static String buildPath(String... directories) {
        StringBuilder ret = new StringBuilder();
        for (String directory : directories) {
            ret.append(directory);
            if (!endsWith(ret, File.separator)) {
                ret.append(File.separator);
            }
        }

        if (directories.length > 0 && endsWith(ret, File.separator)) {
            ret.deleteCharAt(ret.length() - 1);
        }

        if (!ret.substring(ret.lastIndexOf(File.separator)).contains(".")) {
            ret.append(File.separatorChar);
        }

        return ret.toString();
    }

    private static boolean endsWith(StringBuilder sb, String lookup) {
        return sb.substring(sb.length() - lookup.length()).equals(lookup);
    }

    public static void extractRelativePaths(List<String> paths, String basePath, String currentDir) {
        crawlDirectory(paths, basePath, currentDir);
        Collections.sort(paths);
    }

    private static void crawlDirectory(List<String> paths, String basePath, String currentDir) {
        File dir = new File(currentDir);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    String relativePath = file.getAbsolutePath().substring(basePath.length());
                    paths.add(relativePath);
                } else {
                    crawlDirectory(paths, basePath, file.getAbsolutePath());
                }
            }
        }
    }

    public static void deleteAllFilesInDirectory(File directory) throws IOException {
        if (!directory.isDirectory()) {
            String message = "param is not a directory! [directory=%s]";
            message = String.format(message, directory);
            throw new IOException(message);
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    if (!file.delete()) {
                        String message = "Cannot delete file! [file=%s]";
                        message = String.format(message, file.getAbsolutePath());
                        throw new IOException(message);
                    }
                }
            }
        }
    }

    public static void copyAllFiles(File sourceDirectory, File targetDirectory) throws IOException {
        if (sourceDirectory.exists()) {
            if (!sourceDirectory.isDirectory()) {
                String message = "param is not a directory! [sourceDirectory=%s]";
                message = String.format(message, sourceDirectory);
                throw new IOException(message);
            }
        } else {
            getPath(sourceDirectory).mkdirs();
        }

        if (targetDirectory.exists()) {
            if (!targetDirectory.isDirectory()) {
                String message = "param is not a directory! [targetDirectory=%s]";
                message = String.format(message, targetDirectory);
                throw new IOException(message);
            }
        } else {
            getPath(targetDirectory).mkdirs();
        }

        File[] files = sourceDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    copyAllFiles(file, targetDirectory);
                } else {
                    String absolutePath = file.getAbsolutePath();
                    String filename = absolutePath.substring(absolutePath.lastIndexOf(File.separatorChar));
                    copyFile(file, new File(buildPath(targetDirectory.getAbsolutePath(), filename)));
                }
            }
        }
    }

    public static void copyFile(File sourceFile, File targetFile) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(sourceFile);
            out = new FileOutputStream(targetFile);

            byte[] buffer = new byte[1024];

            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
        } finally {
            if (in != null) {
                in.close();
            }

            if (out != null) {
                out.close();
            }
        }
    }

    public static File getPath(File file) {
        String absolutePath = file.getAbsolutePath();
        String lastPart = absolutePath.substring(absolutePath.lastIndexOf(File.separator));
        if (lastPart.contains(".")) {
            return new File(absolutePath.substring(0, absolutePath.lastIndexOf(lastPart)));
        } else {
            return new File(absolutePath);
        }
    }
}