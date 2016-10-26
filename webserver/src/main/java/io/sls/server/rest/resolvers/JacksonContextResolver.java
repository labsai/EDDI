package io.sls.server.rest.resolvers;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.inject.Inject;
import javax.ws.rs.ext.ContextResolver;
import javax.ws.rs.ext.Provider;

/**
 * @author ginccc
 */
@Provider
public class JacksonContextResolver implements ContextResolver<ObjectMapper> {
    private final ObjectMapper objectMapper;

    @Inject
    public JacksonContextResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ObjectMapper getContext(Class<?> aClass) {
        return objectMapper;
    }
}
