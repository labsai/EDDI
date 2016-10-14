package io.sls.core.ui.rest.internal;

import io.sls.core.rest.IRestBotAdministration;
import io.sls.core.runtime.IBot;
import io.sls.core.runtime.IBotFactory;
import io.sls.core.runtime.service.ServiceException;
import io.sls.memory.model.Deployment;
import io.sls.runtime.SystemRuntime;
import io.sls.runtime.ThreadContext;
import io.sls.utilities.RuntimeUtilities;
import org.jboss.resteasy.plugins.guice.RequestScoped;
import org.jboss.resteasy.spi.NoLogWebApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * User: jarisch
 * Date: 27.08.12
 * Time: 15:22
 */
@RequestScoped
public class RestBotAdministration implements IRestBotAdministration {
    private final IBotFactory botFactory;
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Inject
    public RestBotAdministration(IBotFactory botFactory) {
        this.botFactory = botFactory;
    }

    @Override
    public Response deployBot(final Deployment.Environment environment, final String botId, final Integer version) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(version, "version");

        try {
            deploy(environment, botId, version);
            return Response.accepted().build();
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private void deploy(final Deployment.Environment environment, final String botId, final Integer version) {
        Callable<Void> deployBot = () -> {
            try {
                if (Objects.equals(getStatus(environment, botId, version), Deployment.Status.NOT_FOUND.toString())) {
                    botFactory.deployBot(environment, botId, version);
                }
            } catch (ServiceException e) {
                String message = "Error while deploying bot! (botId=%s , version=%s)";
                message = String.format(message, botId, version);
                logger.error(message, e);
                throw new InternalServerErrorException(message, e);
            } catch (IllegalAccessException e) {
                String message = "Bot deployment is currently in progress! (botId=%s , version=%s)";
                message = String.format(message, botId, version);
                logger.error(message, e);
                throw new NoLogWebApplicationException(new Throwable(message), Response.Status.FORBIDDEN);
            } catch (Exception e) {
                logger.error(e.getLocalizedMessage(), e);
                throw new InternalServerErrorException(e.getLocalizedMessage(), e);
            }

            return null;
        };
        SystemRuntime.getRuntime().submitCallable(deployBot, ThreadContext.getResources());
    }

    @Override
    public String getDeploymentStatus(Deployment.Environment environment, String botId, Integer version) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(version, "version");

        return getStatus(environment, botId, version);
    }

    private String getStatus(Deployment.Environment environment, String botId, Integer version) {
        try {
            IBot bot = botFactory.getBot(environment, botId, version);
            if (bot != null) {
                return bot.getDeploymentStatus().toString();
            } else {
                return Deployment.Status.NOT_FOUND.toString();
            }
        } catch (ServiceException e) {
            String message = "Error while deploying bot! (botId=%s , version=%s)";
            message = String.format(message, botId, version);
            logger.error(message, e);
            throw new InternalServerErrorException(message, e);
        }
    }
}
