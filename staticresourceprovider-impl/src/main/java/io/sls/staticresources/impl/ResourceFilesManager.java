package io.sls.staticresources.impl;

import io.sls.staticresources.IResourceDirectory;
import io.sls.staticresources.IResourceFilesManager;
import io.sls.staticresources.impl.contentdelivery.ContentDeliveryPreparation;
import io.sls.utilities.FileUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Copyright by Spoken Language System. All rights reserved.
 * User: jarisch
 * Date: 03.10.12
 * Time: 14:43
 */
public class ResourceFilesManager implements IResourceFilesManager {
    private static final String INTERNAL_JAVASCRIPT_FILES_IDENTIFIER = "INTERNAL_JAVASCRIPT_FILES";
    private static final String EXTERNAL_JAVASCRIPT_FILES_IDENTIFIER = "EXTERNAL_JAVASCRIPT_FILES";
    private static final String CSS_FILES_IDENTIFIER = "CSS_FILES";
    private static final String[] NO_EXCLUDED_DIRS = new String[0];

    private Options options;
    private List<IResourceDirectory> resourceDirectories;
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    private final ContentDeliveryPreparation deliveryPreparation = new ContentDeliveryPreparation();


    public ResourceFilesManager(Options options, List<IResourceDirectory> resourceDirectories) {
        this.options = options;
        this.resourceDirectories = resourceDirectories;
    }

    @Override
    public List<IResourceDirectory> getResourceDirectories() {
        return resourceDirectories;
    }

    @Override
    public IResourceDirectory getResourceDirectory(String keyIdentifier, String targetDevice) {
        for (IResourceDirectory resourceDirectory : resourceDirectories) {
            if (resourceDirectory.getKeyIdentifier().equals(keyIdentifier) &&
                    resourceDirectory.getTargetDevice().equals(targetDevice)) {
                return resourceDirectory;
            }
        }

        String msg = "Could not find resource directory with key identifier: %s";
        msg = String.format(msg, keyIdentifier);
        throw new NoSuchElementException(msg);
    }

    @Override
    public Options getOptions() {
        return options;
    }

    @Override
    public void reloadResourceFiles() {
        long totalStart, totalEnd;
        totalStart = System.currentTimeMillis();
        long start, end;

        for (IResourceDirectory resourceDirectory : resourceDirectories) {
            prepareCSSFiles(resourceDirectory.getResourceInternalCssDir(), resourceDirectory.getWebInternalCssDir(), NO_EXCLUDED_DIRS);
            prepareCSSFiles(resourceDirectory.getResourceExcludedCssDir(), resourceDirectory.getWebExcludedCssDir(), NO_EXCLUDED_DIRS);

            prepareInternalJSFiles(resourceDirectory.getResourceInternalJsDir(), resourceDirectory.getWebInternalJsDir(), NO_EXCLUDED_DIRS);
            prepareExternalJSFiles(resourceDirectory.getResourceExternalJsDir(), resourceDirectory.getWebExternalJsDir(), NO_EXCLUDED_DIRS);
            prepareExternalJSFiles(resourceDirectory.getResourceExcludedJsDir(), resourceDirectory.getWebExcludedJsDir(), NO_EXCLUDED_DIRS);

            copyImages(resourceDirectory.getResourceImageDir(), resourceDirectory.getWebImageDir());

            copyAudio(resourceDirectory.getResourceAudioDir(), resourceDirectory.getWebAudioDir());

            copyBinary(resourceDirectory.getResourceBinaryDir(), resourceDirectory.getWebBinaryDir());

            copyI18NFiles(resourceDirectory.getResourceI18nDir(), resourceDirectory.getWebI18nDir(), NO_EXCLUDED_DIRS);

            //prepare html file [include links to resources]
            try {
                start = System.currentTimeMillis();
                File file = new File(resourceDirectory.getResourceHtmlFile());
                if (file.exists()) {
                    final StringBuilder htmlFile = new StringBuilder(FileUtilities.readTextFromFile(file));
                    includeCssFiles(htmlFile, resourceDirectory.getRootWebDir(), resourceDirectory.getWebInternalCssDir());
                    includeJavascriptFiles(htmlFile, resourceDirectory.getRootWebDir(), resourceDirectory.getWebExternalJsDir(), EXTERNAL_JAVASCRIPT_FILES_IDENTIFIER);
                    includeJavascriptFiles(htmlFile, resourceDirectory.getRootWebDir(), resourceDirectory.getWebInternalJsDir(), INTERNAL_JAVASCRIPT_FILES_IDENTIFIER);
                    FileUtilities.writeTextToFile(new File(resourceDirectory.getWebHtmlFile()), htmlFile.toString());
                    end = System.currentTimeMillis();
                    String successMessage = "html file successfully prepared! (%sms)";
                    successMessage = String.format(successMessage, end - start);
                    logger.info(successMessage);
                } else {
                    String msg = "No html file found. Preparation has been skipped. (path=%s)";
                    msg = String.format(msg, resourceDirectory.getResourceHtmlFile());
                    logger.info(msg);
                }
            } catch (IOException e) {
                logger.error("could not prepare html file", e);
            }
        }

        totalEnd = System.currentTimeMillis();
        String successMessage = "resource files successfully prepared! (total: %sms)";
        successMessage = String.format(successMessage, totalEnd - totalStart);
        logger.info(successMessage);
    }

    private void prepareCSSFiles(String source, String target, String[] excludedDirs) {
        long start;
        long end;
        try {
            start = System.currentTimeMillis();
            File srcDirCss = new File(source);
            File targetDir = new File(target);
            if (targetDir.exists()) {
                FileUtilities.deleteAllFilesInDirectory(targetDir);
            }
            ContentDeliveryPreparation.Options cdpOptions;
            cdpOptions = new ContentDeliveryPreparation.Options(ContentDeliveryPreparation.Options.ContentType.CSS, options.isMergeResourceFiles(), options.isAddFingerprintToResources(), excludedDirs);
            deliveryPreparation.prepareFilesInDirectory(srcDirCss, targetDir, null, cdpOptions);
            end = System.currentTimeMillis();
            String successMessage = "css files successfully prepared! (%sms)";
            successMessage = String.format(successMessage, end - start);
            logger.info(successMessage);
        } catch (Exception e) {
            logger.error("could not prepare css content", e);
        }
    }

    private void prepareInternalJSFiles(String source, String target, String[] excludedDirs) {
        long start;
        long end;
        try {
            start = System.currentTimeMillis();
            File srcDirJs = new File(source);
            File targetDir = new File(target);
            if (targetDir.exists()) {
                FileUtilities.deleteAllFilesInDirectory(targetDir);
            }
            ContentDeliveryPreparation.Options cdpOptions;
            cdpOptions = new ContentDeliveryPreparation.Options(ContentDeliveryPreparation.Options.ContentType.JAVASCRIPT, options.isMergeResourceFiles(), options.isAddFingerprintToResources(), excludedDirs);
            deliveryPreparation.prepareFilesInDirectory(srcDirJs, targetDir, null, cdpOptions);
            end = System.currentTimeMillis();
            String successMessage = "internal js files successfully prepared! (%sms)";
            successMessage = String.format(successMessage, end - start);
            logger.info(successMessage);
        } catch (Exception e) {
            logger.error("could not prepare internal js content", e);
        }
    }

    private void prepareExternalJSFiles(String source, String target, String[] excludedDirs) {
        long start;
        long end;
        try {
            start = System.currentTimeMillis();
            File srcDirJs = new File(source);
            File targetDir = new File(target);
            if (targetDir.exists()) {
                FileUtilities.deleteAllFilesInDirectory(targetDir);
            }
            ContentDeliveryPreparation.Options cdpOptions;
            cdpOptions = new ContentDeliveryPreparation.Options(ContentDeliveryPreparation.Options.ContentType.JAVASCRIPT, false, options.isAddFingerprintToResources(), excludedDirs);
            deliveryPreparation.prepareFilesInDirectory(srcDirJs, targetDir, null, cdpOptions);
            end = System.currentTimeMillis();
            String successMessage = "external js files successfully prepared! (%sms)";
            successMessage = String.format(successMessage, end - start);
            logger.info(successMessage);
        } catch (Exception e) {
            logger.error("could not prepare external js content", e);
        }
    }

    private void copyImages(String source, String target) {
        long start;
        long end;
        try {
            start = System.currentTimeMillis();
            File targetDir = new File(target);
            if (targetDir.exists()) {
                FileUtilities.deleteAllFilesInDirectory(targetDir);
            }
            FileUtilities.copyAllFiles(new File(source), targetDir);
            end = System.currentTimeMillis();
            String successMessage = "image files successfully copied! (%sms)";
            successMessage = String.format(successMessage, end - start);
            logger.info(successMessage);
        } catch (IOException e) {
            logger.error("could not copy images to web directory", e);
        }
    }

    private void copyAudio(String source, String target) {
        long start;
        long end;
        try {
            start = System.currentTimeMillis();
            File targetDir = new File(target);
            if (targetDir.exists()) {
                FileUtilities.deleteAllFilesInDirectory(targetDir);
            }
            FileUtilities.copyAllFiles(new File(source), targetDir);
            end = System.currentTimeMillis();
            String successMessage = "audio files successfully copied! (%sms)";
            successMessage = String.format(successMessage, end - start);
            logger.info(successMessage);
        } catch (IOException e) {
            logger.error("could not copy audio files to web directory", e);
        }
    }

    private void copyBinary(String source, String target) {
        long start;
        long end;
        try {
            start = System.currentTimeMillis();
            File targetDir = new File(target);
            if (targetDir.exists()) {
                FileUtilities.deleteAllFilesInDirectory(targetDir);
            }
            FileUtilities.copyAllFiles(new File(source), targetDir);
            end = System.currentTimeMillis();
            String successMessage = "binary files successfully copied! (%sms)";
            successMessage = String.format(successMessage, end - start);
            logger.info(successMessage);
        } catch (IOException e) {
            logger.error("could not copy binary files to web directory", e);
        }
    }

    private void copyI18NFiles(String source, String destination, String[] excludedDirs) {
        long start;
        long end;
        try {
            start = System.currentTimeMillis();
            File sourceDir = new File(source);
            File targetDir = new File(destination);
            if (targetDir.exists()) {
                FileUtilities.deleteAllFilesInDirectory(targetDir);
            }
            ContentDeliveryPreparation.Options cdpOptions;
            cdpOptions = new ContentDeliveryPreparation.Options(ContentDeliveryPreparation.Options.ContentType.JAVASCRIPT, false, options.isAddFingerprintToResources(), excludedDirs);
            deliveryPreparation.prepareFilesInDirectory(sourceDir, targetDir, null, cdpOptions);
            end = System.currentTimeMillis();
            String successMessage = "i18n files successfully prepared! (%sms)";
            successMessage = String.format(successMessage, end - start);
            logger.info(successMessage);
        } catch (IOException e) {
            logger.error("could not copy i18n files to web directory", e);
        } catch (ContentDeliveryPreparation.PrepareFilesException e) {
            logger.error("could not copy i18n files to web directory", e);
        }
    }

    private void includeCssFiles(StringBuilder editorFile, String rootDir, String cssDir) {
        List<String> relativePaths = new LinkedList<String>();
        FileUtilities.extractRelativePaths(relativePaths, cssDir, cssDir);

        StringBuilder cssScriptTags = new StringBuilder();
        for (String relativePath : relativePaths) {
            cssScriptTags.append("<link rel=\"stylesheet\" type=\"text/css\" href=\"/text");
            relativePath = cssDir.substring(rootDir.length()) + relativePath;
            relativePath = relativePath.replaceAll("\\\\", "/");
            if (!relativePath.startsWith("/")) {
                cssScriptTags.append("/");
            }
            cssScriptTags.append(relativePath);
            cssScriptTags.append("\" />\n");
        }

        int startIndex = editorFile.indexOf(CSS_FILES_IDENTIFIER);
        if (startIndex > -1) {
            editorFile.replace(startIndex, startIndex + CSS_FILES_IDENTIFIER.length(), cssScriptTags.toString());
        } else {
            String message = "Could not found css identifier \"%s\" in html file!";
            message = String.format(message, CSS_FILES_IDENTIFIER);
            logger.error(message);
        }
    }

    private void includeJavascriptFiles(StringBuilder editorFile, String rootDir, String jsDirectory, String identifier) {
        List<String> relativePaths = new LinkedList<String>();
        FileUtilities.extractRelativePaths(relativePaths, jsDirectory, jsDirectory);

        StringBuilder jsScriptTags = new StringBuilder();
        for (String relativePath : relativePaths) {
            relativePath = jsDirectory.substring(rootDir.length()) + relativePath;
            includeJavascriptFile(jsScriptTags, relativePath);
        }

        int startIndex = editorFile.indexOf(identifier);
        if (startIndex > -1) {
            editorFile.replace(startIndex, startIndex + identifier.length(), jsScriptTags.toString());
        } else {
            String message = "Could not found javascript identifier \"%s\" in html file!";
            message = String.format(message, identifier);
            logger.error(message);
        }
    }

    @Override
    public void includeJavascriptFile(StringBuilder jsScriptTags, String relativePath) {
        jsScriptTags.append("<script language=\"JavaScript\" type=\"text/javascript\" src=\"/text/");
        relativePath = relativePath.replaceAll("\\\\", "/");
        jsScriptTags.append(relativePath);
        jsScriptTags.append("\" charset=\"utf-8\"></script>\n");
    }


}
