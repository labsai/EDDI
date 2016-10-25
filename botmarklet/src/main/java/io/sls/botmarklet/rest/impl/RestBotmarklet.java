package io.sls.botmarklet.rest.impl;

import io.sls.botmarklet.BotmarkletCreatorUtility;
import io.sls.botmarklet.BotmarkletResources;
import io.sls.botmarklet.rest.IRestBotmarklet;
import io.sls.runtime.ThreadContext;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class RestBotmarklet implements IRestBotmarklet {
    private final BotmarkletResources botmarkletResources;

    @Inject
    public RestBotmarklet(BotmarkletResources botmarkletResources) {
        this.botmarkletResources = botmarkletResources;
    }

    @Override
    public String read(String environment, String botId) {
        try {
            botmarkletResources.createResources(getCurrentURL());
            List<URL> jsResources = botmarkletResources.getJsResources();
            List<URL> cssResources = botmarkletResources.getCssResources();
            return BotmarkletCreatorUtility.createBotMarklet(getCurrentURL(), environment, botId, jsResources, cssResources);
        } catch (MalformedURLException e) {
            log.error(e.getMessage(), e);
            throw new InternalServerErrorException(e.getMessage(), e);
        }
    }

    private String getCurrentURL() {
        StringBuilder url = new StringBuilder();
        url.append(ThreadContext.get("currentURLProtocol"));
        url.append("://");
        url.append(ThreadContext.get("currentURLHost"));
        url.append(":");
        url.append(ThreadContext.get("currentURLPort"));
        return url.toString();
    }


}
