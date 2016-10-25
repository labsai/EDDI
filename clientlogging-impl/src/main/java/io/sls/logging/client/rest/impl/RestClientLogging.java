package io.sls.logging.client.rest.impl;

import io.sls.logging.client.IClientLogging;
import io.sls.logging.client.rest.IRestClientLogging;

import javax.inject.Inject;
import java.io.InputStream;

/**
 * @author ginccc
 */
public class RestClientLogging implements IRestClientLogging {
    private final IClientLogging clientLogging;

    @Inject
    public RestClientLogging(IClientLogging clientLogging) {
        this.clientLogging = clientLogging;
    }

    @Override
    public InputStream logMessage(String logType, String message) {
        /*clientLogging.log(logType, message, null);
        httpServletResponse.setHeader("Content-Type", "image/gif");
        return RestClientLogging.class.getResourceAsStream("/dummy.gif");*/
        return null;
    }
}
