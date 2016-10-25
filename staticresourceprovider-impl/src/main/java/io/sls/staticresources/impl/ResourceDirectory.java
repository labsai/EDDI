package io.sls.staticresources.impl;

import io.sls.staticresources.IResourceDirectory;
import io.sls.utilities.FileUtilities;

import javax.inject.Inject;
import java.util.LinkedList;

/**
 * @author ginccc
 */
public class ResourceDirectory implements IResourceDirectory {
    public final String keyIdentifier;
    public final String targetDevice;

    public final String rootResourceDir;
    public final String rootWebDir;
    private final String environment;


    @Inject
    public ResourceDirectory(String keyIdentifier, String targetDevice, String resourcesPath, String webPath, String environment) {
        this.keyIdentifier = keyIdentifier;
        this.targetDevice = targetDevice;

        rootResourceDir = resourcesPath;
        rootWebDir = webPath;
        this.environment = environment;
    }

    @Override
    public String getPathOfLanguageFile(String language, String location) {
        LinkedList<String> relativePaths = new LinkedList<String>();
        FileUtilities.extractRelativePaths(relativePaths, getWebI18nDir(), getWebI18nDir());

        for (String relativePath : relativePaths) {
            if (relativePath.contains(language + "_" + location)) {
                return relativePath;
            }
        }

        return null;
    }

    @Override
    public String getKeyIdentifier() {
        return keyIdentifier;
    }

    @Override
    public String getTargetDevice() {
        return targetDevice;
    }

    @Override
    public String getRootResourceDir() {
        return rootResourceDir;
    }

    @Override
    public String getRootWebDir() {
        return rootWebDir;
    }

    @Override
    public String getResourceDir() {
        return FileUtilities.buildPath(rootResourceDir, keyIdentifier, targetDevice);
    }

    @Override
    public String getResourceExternalJsDir() {
        return FileUtilities.buildPath(getResourceDir(), "js", "external");
    }

    @Override
    public String getResourceInternalJsDir() {
        return FileUtilities.buildPath(getResourceDir(), "js", "internal");
    }

    @Override
    public String getResourceExcludedJsDir() {
        return FileUtilities.buildPath(getResourceDir(), "js", "excluded");
    }

    @Override
    public String getResourceInternalCssDir() {
        return FileUtilities.buildPath(getResourceDir(), "css", "internal");
    }

    @Override
    public String getResourceExcludedCssDir() {
        return FileUtilities.buildPath(getResourceDir(), "css", "excluded");
    }

    @Override
    public String getResourceImageDir() {
        return FileUtilities.buildPath(getResourceDir(), "images");
    }

    @Override
    public String getResourceAudioDir() {
        return FileUtilities.buildPath(getResourceDir(), "audio");
    }

    @Override
    public String getResourceBinaryDir() {
        return FileUtilities.buildPath(getResourceDir(), "binary");
    }

    @Override
    public String getResourceKeycloakDir() {
        return FileUtilities.buildPath(rootResourceDir, "keycloak", environment);
    }

    @Override
    public String getResourceI18nDir() {
        return FileUtilities.buildPath(getResourceDir(), "i18n");
    }

    @Override
    public String getResourceHtmlFile() {
        return FileUtilities.buildPath(getResourceDir(), "html", "index.html");
    }

    @Override
    public String getWebDir() {
        return FileUtilities.buildPath(rootWebDir, keyIdentifier, targetDevice);
    }

    @Override
    public String getWebExternalJsDir() {
        return FileUtilities.buildPath(getWebDir(), "js", "external");
    }

    @Override
    public String getWebInternalJsDir() {
        return FileUtilities.buildPath(getWebDir(), "js", "internal");
    }

    @Override
    public String getWebExcludedJsDir() {
        return FileUtilities.buildPath(getWebDir(), "js", "exluded");
    }

    @Override
    public String getWebInternalCssDir() {
        return FileUtilities.buildPath(getWebDir(), "css", "internal");
    }

    @Override
    public String getWebExcludedCssDir() {
        return FileUtilities.buildPath(getWebDir(), "css", "excluded");
    }

    @Override
    public String getWebImageDir() {
        return FileUtilities.buildPath(getWebDir(), "images");
    }

    @Override
    public String getWebAudioDir() {
        return FileUtilities.buildPath(getWebDir(), "audio");
    }

    @Override
    public String getWebBinaryDir() {
        return FileUtilities.buildPath(getWebDir(), "binary");
    }

    @Override
    public String getWebKeycloakDir() {
        return FileUtilities.buildPath(rootWebDir, "keycloak");
    }

    @Override
    public String getWebI18nDir() {
        return FileUtilities.buildPath(getWebDir(), "i18n");
    }

    @Override
    public String getWebHtmlFile() {
        return FileUtilities.buildPath(getWebDir(), "html", "index.html");
    }
}
