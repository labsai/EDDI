package ai.labs.nlp;

import ai.labs.permission.interceptor.PermissionRequestInterceptor;
import ai.labs.permission.interceptor.PermissionResponseInterceptor;
import ai.labs.resources.impl.interceptors.DocumentDescriptorInterceptor;
import ai.labs.server.JacksonConfig;
import ai.labs.server.exception.IllegalArgumentExceptionMapper;
import ai.labs.server.providers.URIMessageBodyProvider;
import ai.labs.staticresources.impl.interceptor.ContentTypeInterceptor;
import io.swagger.jaxrs.listing.ApiListingResource;
import io.swagger.jaxrs.listing.SwaggerSerializers;

import javax.ws.rs.core.Application;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * @author ginccc
 */
public class ApplicationConfiguration extends Application {
    private final Set<Class<?>> CLASSES;

    public ApplicationConfiguration() {
        Set<Class<?>> tmp = new LinkedHashSet<>();

        tmp.add(ContentTypeInterceptor.class);
        tmp.add(PermissionRequestInterceptor.class);
        tmp.add(PermissionResponseInterceptor.class);
        tmp.add(DocumentDescriptorInterceptor.class);
        tmp.add(IllegalArgumentExceptionMapper.class);
        tmp.add(JacksonConfig.class);

        /*todo tmp.add(AuthorizationHeaderFilter.class);*/

        tmp.add(ApiListingResource.class);
        tmp.add(SwaggerSerializers.class);
        tmp.add(URIMessageBodyProvider.class);

        CLASSES = Collections.unmodifiableSet(tmp);
    }

    @Override
    public Set<Class<?>> getClasses() {
        return CLASSES;
    }
}
