package io.sls.logging.client.impl;

import io.sls.logging.client.IClientLogging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;


/**
 * Copyright by Spoken Language System. All rights reserved.
 * User: jarisch
 * Date: 26.01.13
 * Time: 21:03
 */
public class ClientLogging implements IClientLogging {
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    private static final String DEBUG = "debug";
    private static final String INFO = "info";
    private static final String WARN = "warn";
    private static final String ERROR = "error";

    @Override
    public void log(String logType, final String msg, final HttpServletRequest httpServletRequest) {
        String remoteAddr = httpServletRequest.getRemoteAddr();
        String message = "Client (%s): %s";
        message = String.format(message, remoteAddr, msg);

        if (DEBUG.equals(logType)) {
            logger.debug(message);
        } else if (INFO.equals(logType)) {
            logger.info(message);
        } else if (WARN.equals(logType)) {
            logger.warn(message);
        } else {
            logger.error(message);
            logger.error("User-Agent: " + httpServletRequest.getHeader("User-Agent"));
        }
    }
}
