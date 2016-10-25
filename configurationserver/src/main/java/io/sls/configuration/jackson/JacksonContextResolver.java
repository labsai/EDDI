package io.sls.configuration.jackson;

import io.sls.serialization.JSONSerialization;
import org.codehaus.jackson.map.ObjectMapper;

import javax.ws.rs.ext.ContextResolver;
import javax.ws.rs.ext.Provider;

/**
 * @author ginccc
 */
@Provider
public class JacksonContextResolver implements ContextResolver<ObjectMapper> {
    @Override
    public ObjectMapper getContext(Class<?> aClass) {
        return JSONSerialization.getObjectMapper();
    }
}
