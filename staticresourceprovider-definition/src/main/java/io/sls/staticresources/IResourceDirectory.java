package io.sls.staticresources;

/**
 * Created by jariscgr on 29.08.2016.
 */
public interface IResourceDirectory {
    String getPathOfLanguageFile(String language, String location);

    String getKeyIdentifier();

    String getTargetDevice();

    String getRootResourceDir();

    String getRootWebDir();

    String getResourceDir();

    String getResourceExternalJsDir();

    String getResourceInternalJsDir();

    String getResourceExcludedJsDir();

    String getResourceInternalCssDir();

    String getResourceExcludedCssDir();

    String getResourceImageDir();

    String getResourceAudioDir();

    String getResourceBinaryDir();

    String getResourceI18nDir();

    String getResourceHtmlFile();

    String getWebDir();

    String getWebExternalJsDir();

    String getWebInternalJsDir();

    String getWebExcludedJsDir();

    String getWebInternalCssDir();

    String getWebExcludedCssDir();

    String getWebImageDir();

    String getWebAudioDir();

    String getWebBinaryDir();

    String getWebI18nDir();

    String getWebHtmlFile();
}
