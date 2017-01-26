package ai.labs.staticresources.impl.contentdelivery;

import ai.labs.utilities.FileUtilities;
import ai.labs.utilities.RuntimeUtilities;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class ContentDeliveryPreparation {

    private final FileMerger merger;
    private final FingerprintGenerator fingerprintGenerator;

    public ContentDeliveryPreparation() {
        merger = new FileMerger();
        fingerprintGenerator = new FingerprintGenerator();
    }

    public static class Options {
        public Options() {
        }

        public Options(ContentType contentType, boolean mergeFiles, boolean addFingerprint, String[] excludedDirs) {
            this.contentType = contentType;
            this.mergeFiles = mergeFiles;
            this.addFingerprint = addFingerprint;
            this.excludedDirs = excludedDirs;
        }

        public enum ContentType {
            JAVASCRIPT,
            CSS,
            OTHER
        }

        public ContentType contentType = ContentType.JAVASCRIPT;
        public boolean mergeFiles = true;
        public boolean addFingerprint = true;
        public String[] excludedDirs = new String[]{};
    }

    public void prepareFilesInDirectory(File sourceDirectory, File targetDirectory, String targetFileName, Options options) throws PrepareFilesException {
        RuntimeUtilities.checkNotNull(sourceDirectory, "sourceDirectory");
        RuntimeUtilities.checkNotNull(targetDirectory, "targetDirectory");
        RuntimeUtilities.checkNotNull(options, "options");

        if (sourceDirectory.exists()) {
            if (!sourceDirectory.isDirectory()) {
                throw new PrepareFilesException("sourceDirectory is not a directory!");
            }
        } else {
            FileUtilities.getPath(sourceDirectory).mkdirs();
        }

        if (targetDirectory.exists()) {
            if (!targetDirectory.isDirectory()) {
                throw new PrepareFilesException("targetDirectory is not a directory!");
            }
        } else {
            FileUtilities.getPath(targetDirectory).mkdirs();
        }

        List<String> paths = new LinkedList<String>();
        FileUtilities.extractRelativePaths(paths, sourceDirectory.getAbsolutePath(), sourceDirectory.getAbsolutePath());
        try {
            //merge files
            List<File> files = pathsToFile(paths, sourceDirectory.getAbsolutePath());
            Map<String, String> contents = new HashMap<String, String>();
            if (options.contentType != Options.ContentType.OTHER && options.mergeFiles) {
                if (targetFileName == null) {
                    targetFileName = "combined" + (options.contentType == Options.ContentType.JAVASCRIPT ? ".js" : ".css");
                } else {
                    targetFileName = null;
                }

                contents.put(FileUtilities.buildPath(targetDirectory.getAbsolutePath(), targetFileName), merger.mergeFiles(files));
            } else {
                contents = merger.readFiles(files);
            }

            for (String path : contents.keySet()) {
                boolean exclude = false;
                for (String excludedDir : options.excludedDirs) {
                    if (path.startsWith(excludedDir)) {
                        exclude = true;
                        break;
                    }
                }
                if (exclude) {
                    continue;
                }

                String content = contents.get(path);

                if (targetFileName == null) {
                    targetFileName = path.substring(path.lastIndexOf(File.separatorChar) + 1);
                }
                String targetFile;
                if (options.addFingerprint) {
                    //add fingerprint
                    String fingerprint = fingerprintGenerator.calculateFingerprint(content, FingerprintGenerator.FingerprintType.HASH);
                    targetFile = FileUtilities.buildPath(targetDirectory.getAbsolutePath(), fingerprintGenerator.addFingerprintToFileName(targetFileName, fingerprint));
                } else {
                    targetFile = FileUtilities.buildPath(targetDirectory.getAbsolutePath(), targetFileName);
                }

                targetFileName = null;

                //save content to file
                FileUtilities.writeTextToFile(new File(targetFile), content);
            }
        } catch (FileMerger.FileMergeException e) {
            throw new PrepareFilesException(e.getLocalizedMessage(), e);
        } catch (IOException e) {
            throw new PrepareFilesException(e.getLocalizedMessage(), e);
        }
    }

    private List<File> pathsToFile(List<String> paths, String baseDir) {
        List<File> files = new LinkedList<File>();

        for (String path : paths) {
            files.add(new File(baseDir + path));
        }

        return files;
    }

    public class PrepareFilesException extends Exception {
        public PrepareFilesException(String message, Throwable e) {
            super(message, e);
        }

        public PrepareFilesException(String message) {
            super(message);
        }
    }
}
