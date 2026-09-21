/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.rest.providers;

import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.model.Deployment;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ext.ParamConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EnvironmentParamConverterProvider")
class EnvironmentParamConverterProviderTest {

    private final EnvironmentParamConverterProvider provider = new EnvironmentParamConverterProvider();

    private ParamConverter<Deployment.Environment> converter() {
        ParamConverter<Deployment.Environment> converter = provider.getConverter(Deployment.Environment.class, Deployment.Environment.class,
                new Annotation[0]);
        assertNotNull(converter, "the provider must claim Deployment.Environment");
        return converter;
    }

    @Test
    @DisplayName("claims only Deployment.Environment")
    void claimsOnlyEnvironment() {
        assertNull(provider.getConverter(String.class, String.class, new Annotation[0]));
        assertNull(provider.getConverter(Deployment.Status.class, Deployment.Status.class, new Annotation[0]));
    }

    @Test
    @DisplayName("parses the real environments and the v5 aliases")
    void parsesKnownValues() {
        assertEquals(Deployment.Environment.production, converter().fromString("production"));
        assertEquals(Deployment.Environment.test, converter().fromString("test"));
        assertEquals(Deployment.Environment.test, converter().fromString(" TEST "));
        assertEquals(Deployment.Environment.production, converter().fromString("unrestricted"));
        assertEquals(Deployment.Environment.production, converter().fromString("restricted"));
    }

    @Test
    @DisplayName("an unknown environment is a 400 naming the valid values, never production")
    void rejectsUnknownEnvironment() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> converter().fromString("staging"));

        assertEquals(400, e.getResponse().getStatus());
        String body = String.valueOf(e.getResponse().getEntity());
        assertTrue(body.contains("staging"), "the message must name the rejected value: " + body);
        assertTrue(body.contains(Deployment.Environment.VALID_ENVIRONMENTS), "the message must list the valid values: " + body);
    }

    @Test
    @DisplayName("an absent optional parameter stays null")
    void nullStaysNull() {
        assertNull(converter().fromString(null));
        assertNull(converter().toString(null));
    }

    @Test
    @DisplayName("round-trips to the enum name")
    void rendersEnumName() {
        assertEquals("test", converter().toString(Deployment.Environment.test));
    }

    /**
     * Guards the premise of the provider: the environment reaches these resources
     * as the enum type, so JAX-RS consults a ParamConverterProvider before any
     * static fromString. A later refactor to a String parameter would bypass the
     * strict parse silently, so it must fail here instead.
     */
    @Test
    @DisplayName("the deployment and conversation-start resources still bind the enum type")
    void resourcesBindTheEnumType() {
        List<String> bound = new ArrayList<>();
        for (Class<?> resource : List.of(IRestAgentAdministration.class, IRestAgentEngine.class)) {
            for (Method method : resource.getMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    String name = parameter.isAnnotationPresent(PathParam.class)
                            ? parameter.getAnnotation(PathParam.class).value()
                            : parameter.isAnnotationPresent(QueryParam.class) ? parameter.getAnnotation(QueryParam.class).value() : null;
                    if ("environment".equals(name)) {
                        assertEquals(Deployment.Environment.class, parameter.getType(),
                                resource.getSimpleName() + "." + method.getName() + " binds 'environment' as " + parameter.getType());
                        bound.add(resource.getSimpleName() + "." + method.getName());
                    }
                }
            }
        }
        assertTrue(bound.size() >= 6, "expected deploy/undeploy/status/statuses and both conversation starts, got " + bound);
    }
}
