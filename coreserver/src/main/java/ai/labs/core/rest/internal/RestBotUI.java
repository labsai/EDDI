package ai.labs.core.rest.internal;

import ai.labs.memory.model.Deployment;
import ai.labs.rest.rest.IRestBotUI;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.plugins.guice.RequestScoped;

import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Context;
import java.io.IOException;

/**
 * @author ginccc
 */
@RequestScoped
@Slf4j
public class RestBotUI implements IRestBotUI {
    private final HttpServletRequest httpServletRequest;
    private final HttpServletResponse httpServletResponse;

    @Inject
    public RestBotUI(@Context HttpServletRequest httpServletRequest,
                     @Context HttpServletResponse httpServletResponse) {
        this.httpServletRequest = httpServletRequest;
        this.httpServletResponse = httpServletResponse;
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
