package io.sls.core.ui.rest.internal;

import io.sls.core.rest.IRestBotUI;
import io.sls.faces.html.IHtmlFaceStore;
import io.sls.faces.html.model.HtmlFace;
import io.sls.memory.model.Deployment;
import io.sls.persistence.IResourceStore;
import io.sls.runtime.ThreadContext;
import io.sls.staticresources.IResourceDirectory;
import io.sls.staticresources.IResourceFilesManager;
import io.sls.utilities.FileUtilities;
import io.sls.utilities.HtmlUtilities;
import io.sls.utilities.RuntimeUtilities;
import org.jboss.resteasy.plugins.guice.RequestScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Context;
import java.io.File;
import java.io.IOException;

/**
 * User: jarisch
 * Date: 14.09.12
 * Time: 10:19
 */
@RequestScoped
public class RestBotUI implements IRestBotUI {
    private static final String USER_DISPLAY_NAME = "USER_DISPLAY_NAME";
    private static final String LOGOUT_URL = "LOGOUT_URL";
    public static final String LANGUAGE_IDENTIFIER = "LANGUAGE_FILE";
    public static final String MOBILE = "mobile";
    public static final String DESKTOP = "desktop";
    public static final String BODY_IDENTIFIER = "<body>";
    private final HttpServletRequest httpServletRequest;
    private final HttpServletResponse httpServletResponse;
    private final IHtmlFaceStore faceStore;
    private final IResourceFilesManager resourceFilesManager;
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Inject
    public RestBotUI(@Context HttpServletRequest httpServletRequest,
                     @Context HttpServletResponse httpServletResponse,
                     IHtmlFaceStore faceStore,
                     IResourceFilesManager resourceFilesManager) {
        this.httpServletRequest = httpServletRequest;
        this.httpServletResponse = httpServletResponse;
        this.faceStore = faceStore;
        this.resourceFilesManager = resourceFilesManager;
    }

    @Override
    public String viewBotUI(Deployment.Environment environment,
                            String botId,
                            String language,
                            String location,
                            String uiIdentifier,
                            String targetDevice) {
        try {
            if (environment != Deployment.Environment.unrestricted && httpServletRequest.getRemoteUser() == null) {
                httpServletRequest.authenticate(httpServletResponse);
                return null;
            }

            if (resourceFilesManager.getOptions().alwaysReloadResourcesFile()) {
                resourceFilesManager.reloadResourceFiles();
            }

            if (!DESKTOP.equals(targetDevice) && !MOBILE.equals(targetDevice)) {
                targetDevice = DESKTOP;
            }

            try {
                HtmlFace htmlFace = faceStore.searchFaceByBotId(botId);
                if (RuntimeUtilities.isNullOrEmpty(uiIdentifier)) {
                    uiIdentifier = htmlFace.getUiIdentifier();
                }
                if (RuntimeUtilities.isNullOrEmpty(language)) {
                    language = htmlFace.getUiLanguage();
                }
                if (RuntimeUtilities.isNullOrEmpty(location)) {
                    location = htmlFace.getUiLocation();
                }
            } catch (IResourceStore.ResourceNotFoundException e) {
                if (RuntimeUtilities.isNullOrEmpty(botId)) {
                    throw e;
                }

                if (RuntimeUtilities.isNullOrEmpty(uiIdentifier)) {
                    uiIdentifier = "default";
                }
            }

            IResourceDirectory resourceDirectory = resourceFilesManager.getResourceDirectory(uiIdentifier, targetDevice);
            File htmlFile = new File(resourceDirectory.getWebHtmlFile());
            final StringBuilder htmlContent = new StringBuilder(FileUtilities.readTextFromFile(htmlFile));
            includeUserDisplayName(htmlContent);

            includeLogoutUrl(htmlContent, httpServletRequest.getRequestURI());
            includeLanguageFile(htmlContent, resourceDirectory, language, location);
            Object userId = ThreadContext.get("currentuser:userid");
            if (userId == null) {
                userId = "";
            }
            includeHiddenValues(htmlContent, environment, botId, userId.toString());
            includeRestApiHostScriptTag(htmlContent);
            return htmlContent.toString();
        } catch (IOException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceStoreException | ServletException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public String viewUI(Deployment.Environment environment, String language, String location, String targetDevice) {
        try {
            String serverName = httpServletRequest.getServerName();
            HtmlFace htmlFace = faceStore.searchFaceByHost(serverName);
            String botId = htmlFace.getBotId();
            String uiIdentifier = htmlFace.getUiIdentifier();
            return viewBotUI(environment, botId, language, location, uiIdentifier, targetDevice);
        } catch (IResourceStore.ResourceNotFoundException e) {
            logger.error(e.getMessage(), e);
            throw new NotFoundException(e.getMessage(), e);
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public String viewUI(String language, String location, String targetDevice) {
        try {
            String serverName = httpServletRequest.getServerName();
            HtmlFace htmlFace = faceStore.searchFaceByHost(serverName);
            return viewUI(htmlFace.getEnvironment(), language, location, targetDevice);
        } catch (IResourceStore.ResourceNotFoundException e) {
            logger.error(e.getMessage(), e);
            throw new NotFoundException(e.getMessage(), e);
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private HtmlFace getHtmlFace(String serverName) throws IResourceStore.ResourceStoreException {
        HtmlFace htmlFace;
        try {
            htmlFace = faceStore.searchFaceByHost(serverName);
        } catch (IResourceStore.ResourceNotFoundException e) {
            htmlFace = new HtmlFace();
            htmlFace.setUiIdentifier("default");
        }
        return htmlFace;
    }


    private void includeLanguageFile(StringBuilder htmlFile, IResourceDirectory resourceDirectory, String language, String location) {
        StringBuilder scriptTag = new StringBuilder();
        String pathOfLanguageFile = resourceDirectory.getPathOfLanguageFile(language, location);
        String relativePath = resourceDirectory.getWebI18nDir().substring(resourceDirectory.getRootWebDir().length()) + pathOfLanguageFile;
        resourceFilesManager.includeJavascriptFile(scriptTag, relativePath);
        int startIndex = htmlFile.indexOf(LANGUAGE_IDENTIFIER);
        htmlFile.replace(startIndex, startIndex + LANGUAGE_IDENTIFIER.length(), scriptTag.toString());
    }

    private void includeHiddenValues(StringBuilder htmlFile, Deployment.Environment environment, String botId, String currentUserId) {
        htmlFile.insert(htmlFile.indexOf(BODY_IDENTIFIER) + BODY_IDENTIFIER.length(), createHiddenField("currentUserId", currentUserId));
        htmlFile.insert(htmlFile.indexOf(BODY_IDENTIFIER) + BODY_IDENTIFIER.length(), createHiddenField("botId", botId));
        htmlFile.insert(htmlFile.indexOf(BODY_IDENTIFIER) + BODY_IDENTIFIER.length(), createHiddenField("environment", environment.toString()));
    }

    private String createHiddenField(String name, String value) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n<input type=\"hidden\" ");
        sb.append("id=\"").append(name).append("\" ");
        sb.append("name=\"").append(name).append("\" ");
        sb.append("value=\"").append(value).append("\">\n");
        return sb.toString();
    }

    private void includeUserDisplayName(StringBuilder editorFile) {
        int startIndex = editorFile.indexOf(USER_DISPLAY_NAME);
        String currentUserDisplayName = (String) ThreadContext.get("currentuser:displayname");
        if (startIndex != -1 && currentUserDisplayName != null) {
            editorFile.replace(startIndex, startIndex + USER_DISPLAY_NAME.length(), currentUserDisplayName);
        }
    }

    private void includeLogoutUrl(StringBuilder editorFile, String currentPath) {
        int startIndex = editorFile.indexOf(LOGOUT_URL);
        if (startIndex != -1) {
            editorFile.replace(startIndex, startIndex + LOGOUT_URL.length(), getLogoutUrl(currentPath));
        }
    }

    private String getLogoutUrl(String currentPath) {
        StringBuilder logoutUrl = new StringBuilder();
        Integer currentUrlPort = getCurrentUrlPort();
        logoutUrl.append(getCurrentUrlProtocol()).append("://").append("logout:true@").append(getCurrentUrlHost());
        if (currentUrlPort != -1 && currentUrlPort != 80 && currentUrlPort != 443) {
            logoutUrl.append(":").append(currentUrlPort);
        }
        logoutUrl.append(currentPath);
        return logoutUrl.toString();
    }

    private void includeRestApiHostScriptTag(StringBuilder editorFile) {
        StringBuilder javascriptInjection = new StringBuilder();
        javascriptInjection.append(" REST.apiURL = '");
        javascriptInjection.append(getCurrentUrlProtocol()).append("://").append(getCurrentUrlHost()).append(":").append(getCurrentUrlPort()).append("';");
        String javascriptStatement = HtmlUtilities.wrapInJavascriptStatement(javascriptInjection.toString());
        editorFile.insert(editorFile.indexOf("</head>"), javascriptStatement);
    }

    private String getCurrentUrlProtocol() {
        return (String) ThreadContext.get("currentURLProtocol");
    }

    private String getCurrentUrlHost() {
        return (String) ThreadContext.get("currentURLHost");
    }

    private Integer getCurrentUrlPort() {
        return (Integer) ThreadContext.get("currentURLPort");
    }

}
