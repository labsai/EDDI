package io.sls.logging.client;

import javax.servlet.http.HttpServletRequest;

/**
 * @author ginccc
 */
public interface IClientLogging {
    void log(String logType, String message, HttpServletRequest httpServletRequest);
}
