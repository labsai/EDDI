package ai.labs.server.providers;

import ai.labs.runtime.ThreadContext;
import ai.labs.utilities.RuntimeUtilities;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Authentication;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;


/**
 * set credentials for internal rest calls on behave of the user
 */
public class CorsBasicAuthenticator extends BasicAuthenticator {
    @Override
    public Authentication validateRequest(ServletRequest req, ServletResponse res, boolean mandatory) throws ServerAuthException {
        HttpServletRequest request = (HttpServletRequest) req;
        String credentials = request.getHeader(HttpHeader.AUTHORIZATION.asString());
        if (!RuntimeUtilities.isNullOrEmpty(credentials)) {
            ThreadContext.put("currentuser:credentials", credentials);
        }

        return super.validateRequest(req, res, mandatory);
    }
}
