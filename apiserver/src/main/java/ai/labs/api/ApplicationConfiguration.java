package ai.labs.api;

import ai.labs.permission.interceptor.PermissionRequestInterceptor;
import ai.labs.permission.interceptor.PermissionResponseInterceptor;
import ai.labs.resources.impl.interceptors.DocumentDescriptorInterceptor;
import ai.labs.server.JacksonConfig;
import ai.labs.server.exception.IllegalArgumentExceptionMapper;
import ai.labs.server.providers.KeycloakAuthorizationHeaderFilter;
import ai.labs.server.providers.URIMessageBodyProvider;
import ai.labs.staticresources.impl.interceptor.ContentTypeInterceptor;

import javax.ws.rs.core.Application;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * @author ginccc
 */
public class ApplicationConfiguration extends Application {
    private static Set<Class<?>> CLASSES;

    static {
        Set<Class<?>> tmp = new LinkedHashSet<>();

        tmp.add(ContentTypeInterceptor.class);
        tmp.add(PermissionRequestInterceptor.class);
        tmp.add(PermissionResponseInterceptor.class);
        tmp.add(DocumentDescriptorInterceptor.class);
        tmp.add(IllegalArgumentExceptionMapper.class);
        tmp.add(JacksonConfig.class);
        tmp.add(URIMessageBodyProvider.class);
        tmp.add(KeycloakAuthorizationHeaderFilter.class);

        CLASSES = Collections.unmodifiableSet(tmp);
    }

    @Override
    public Set<Class<?>> getClasses() {
        return CLASSES;
    }
}
