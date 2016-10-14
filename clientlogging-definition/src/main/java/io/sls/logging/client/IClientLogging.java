package io.sls.logging.client;

import javax.servlet.http.HttpServletRequest;

/**
 * User: jarisch
 * Date: 26.01.13
 * Time: 21:03
 */
public interface IClientLogging {
    void log(String logType, String message, HttpServletRequest httpServletRequest);
}
