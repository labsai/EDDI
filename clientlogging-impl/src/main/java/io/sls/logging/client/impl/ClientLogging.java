package io.sls.logging.client.impl;

import io.sls.logging.client.IClientLogging;
import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpServletRequest;


/**
 * User: jarisch
 * Date: 26.01.13
 * Time: 21:03
 */
@Slf4j
public class ClientLogging implements IClientLogging {
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
            log.debug(message);
        } else if (INFO.equals(logType)) {
            log.info(message);
        } else if (WARN.equals(logType)) {
            log.warn(message);
        } else {
            log.error(message);
            log.error("User-Agent: " + httpServletRequest.getHeader("User-Agent"));
        }
    }
}
