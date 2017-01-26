package ai.labs.core.rest.internal;

import ai.labs.memory.model.Deployment;
import ai.labs.rest.rest.IRestBotUI;
import ai.labs.staticresources.IResourceDirectory;
import ai.labs.staticresources.IResourceFilesManager;
import ai.labs.utilities.FileUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.plugins.guice.RequestScoped;

import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Context;
import java.io.File;
import java.io.IOException;

/**
 * @author ginccc
 */
@RequestScoped
@Slf4j
public class RestBotUI implements IRestBotUI {
    private final HttpServletRequest httpServletRequest;
    private final HttpServletResponse httpServletResponse;
    private final IResourceFilesManager resourceFilesManager;

    @Inject
    public RestBotUI(@Context HttpServletRequest httpServletRequest,
                     @Context HttpServletResponse httpServletResponse,
                     IResourceFilesManager resourceFilesManager) {
        this.httpServletRequest = httpServletRequest;
        this.httpServletResponse = httpServletResponse;
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

            IResourceDirectory resourceDirectory = resourceFilesManager.getResourceDirectory(uiIdentifier, targetDevice);
            File htmlFile = new File(resourceDirectory.getWebHtmlFile());
            final StringBuilder htmlContent = new StringBuilder(FileUtilities.readTextFromFile(htmlFile));
         /*   includeUserDisplayName(htmlContent);

            includeLogoutUrl(htmlContent, httpServletRequest.getRequestURI());
            includeLanguageFile(htmlContent, resourceDirectory, language, location);
            Object userId = ThreadContext.get("currentuser:userid");
            if (userId == null) {
                userId = "";
            }
            includeHiddenValues(htmlContent, environment, botId, userId.toString());
            includeRestApiHostScriptTag(htmlContent);
            return htmlContent.toString();*/
            return null;
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (ServletException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public String viewUI(Deployment.Environment environment, String language, String location, String targetDevice) {
        return null;
    }

    @Override
    public String viewUI(String language, String location, String targetDevice) {
        return null;
    }
}
