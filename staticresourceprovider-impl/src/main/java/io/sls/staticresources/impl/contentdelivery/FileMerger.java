package io.sls.staticresources.impl.contentdelivery;

import io.sls.utilities.FileUtilities;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * @author ginccc
 */
public class FileMerger {
    public String mergeFiles(List<File> files) throws FileMergeException {
        StringBuilder targetFileContent = new StringBuilder();

        try {
            Map<String, String> fileContents = readFiles(files);
            List<String> keys = new LinkedList<>(fileContents.keySet());

            Collections.sort(keys, (o1, o2) -> {
                String filename1;
                if (o1.contains(File.separator)) {
                    filename1 = o1.substring(o1.lastIndexOf(File.separatorChar) + 1);
                } else {
                    filename1 = o1;
                }
                String filename2;
                if (o2.contains(File.separator)) {
                    filename2 = o2.substring(o2.lastIndexOf(File.separatorChar) + 1);
                } else {
                    filename2 = o2;
                }

                return filename1.compareTo(filename2);
            });

            for (String key : keys) {
                targetFileContent.append(fileContents.get(key)).append("\n\n");
            }

            return targetFileContent.toString();
        } catch (IOException e) {
            throw new FileMergeException("Error while merging files.", e);
        }
    }

    public Map<String, String> readFiles(List<File> files) throws IOException {
        Map<String, String> contents = new HashMap<>();
        for (File file : files) {
            contents.put(file.getAbsolutePath(), FileUtilities.readTextFromFile(file));
        }

        return contents;
    }

    public static class FileMergeException extends Throwable {
        public FileMergeException(String message, Throwable e) {
            super(message, e);
        }
    }
}
