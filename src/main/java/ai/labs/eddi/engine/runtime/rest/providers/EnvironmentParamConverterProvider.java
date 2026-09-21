/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.rest.providers;

import ai.labs.eddi.engine.model.Deployment;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * Binds every JAX-RS {@code @PathParam}/{@code @QueryParam} of type
 * {@link Deployment.Environment} through the STRICT parser.
 * <p>
 * Without this provider JAX-RS falls back to the enum's static
 * {@link Deployment.Environment#fromString(String)}, which is deliberately
 * lenient for stored documents and maps an unknown name to {@code production}.
 * On a request that meant {@code POST /administration/staging/deploy/{id}}
 * deployed to — and {@code .../staging/undeploy/{id}} undeployed from — the
 * live environment, and {@code GET /administration/staging/deploymentstatus}
 * listed it. An environment named in a request is acted on, so it must be
 * parsed the way every other acting boundary parses it: an unknown value is a
 * 400 naming the valid ones, never a silent redirect to production.
 * <p>
 * The rejection is a {@link BadRequestException} rather than an
 * {@link IllegalArgumentException}: JAX-RS turns a converter's non-web
 * exception into a 404 for path parameters, which would read as "no such agent"
 * instead of "no such environment".
 */
@Provider
public class EnvironmentParamConverterProvider implements ParamConverterProvider {

    static final ParamConverter<Deployment.Environment> STRICT = new ParamConverter<>() {
        @Override
        public Deployment.Environment fromString(String value) {
            if (value == null) {
                // An absent optional query parameter without @DefaultValue stays absent.
                return null;
            }
            try {
                return Deployment.Environment.parseStrict(value);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
                        .entity(e.getMessage())
                        .type(MediaType.TEXT_PLAIN)
                        .build());
            }
        }

        @Override
        public String toString(Deployment.Environment value) {
            return value == null ? null : value.name();
        }
    };

    @Override
    @SuppressWarnings("unchecked")
    public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
        if (rawType != Deployment.Environment.class) {
            return null;
        }
        return (ParamConverter<T>) STRICT;
    }
}
