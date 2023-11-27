package ai.labs.eddi.engine.httpclient;

import org.eclipse.jetty.http.HttpCookieStore;

import java.net.URI;

public interface IHttpClient {
    enum Method {
        HEAD,
        GET,
        POST,
        PUT,
        DELETE,
        PATCH
    }

    HttpCookieStore getCookieStore();

    IRequest newRequest(URI uri);

    IRequest newRequest(URI uri, Method method);
}
