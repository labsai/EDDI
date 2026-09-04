/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.bootstrap;

import ai.labs.eddi.engine.httpclient.impl.VertxHttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CDI wiring of the one and only {@link VertxHttpClient} producer — i.e. of
 * every outbound httpCalls request in every agent.
 * <p>
 * This declaration carried {@code jakarta.ws.rs.Produces}, the JAX-RS
 * media-type annotation of the same simple name, and the bean existed anyway
 * only because ArC's {@code quarkus.arc.auto-producer-methods} default promotes
 * any method carrying a scope annotation to a producer. Setting that default to
 * false — or moving {@code @ApplicationScoped} off the method, e.g. to make the
 * client {@code @Singleton} through a stereotype — would have left
 * {@code VertxHttpClient} unsatisfied, with nothing in the build failure
 * pointing at the wrong import as the cause. The paired {@code @Disposes} half
 * was always the real CDI annotation, so the two disagreed.
 * <p>
 * A Quarkus boot is the only thing that exercises the wiring end to end, and
 * this codebase has no test that boots for this bean; the annotation itself is
 * the assertable seam, so that is what this pins.
 */
@DisplayName("HttpClientModule CDI wiring")
class HttpClientModuleTest {

    private static Method producerMethod() {
        return Arrays.stream(HttpClientModule.class.getDeclaredMethods())
                .filter(m -> "provideHttpClient".equals(m.getName()))
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("the producer carries the CDI @Produces, not the JAX-RS one of the same simple name")
    void producerUsesTheCdiProducesAnnotation() {
        Method producer = producerMethod();
        assertNotNull(producer, "HttpClientModule.provideHttpClient is the sole IHttpClient producer");

        assertTrue(producer.isAnnotationPresent(Produces.class),
                "jakarta.enterprise.inject.Produces is what makes this a CDI producer; without it the bean "
                        + "exists only by grace of quarkus.arc.auto-producer-methods");
        assertFalse(producer.isAnnotationPresent(jakarta.ws.rs.Produces.class),
                "jakarta.ws.rs.Produces is the media-type annotation and declares nothing here");
        assertTrue(producer.isAnnotationPresent(ApplicationScoped.class),
                "one client instance per application, disposed by the @Disposes half below");
        assertTrue(VertxHttpClient.class.isAssignableFrom(producer.getReturnType()),
                "the produced type is what every httpCall resolves");
    }

    @Test
    @DisplayName("the disposer half stays paired with the producer")
    void disposerIsPairedWithTheProducer() {
        Method disposer = Arrays.stream(HttpClientModule.class.getDeclaredMethods())
                .filter(m -> Arrays.stream(m.getParameters()).anyMatch(p -> p.isAnnotationPresent(Disposes.class)))
                .findFirst()
                .orElse(null);

        assertNotNull(disposer, "a producer with no disposer leaks the client's connection pool on shutdown");
        assertTrue(Arrays.stream(disposer.getParameters())
                .anyMatch(p -> VertxHttpClient.class.isAssignableFrom(p.getType())),
                "the disposer must dispose the type the producer produces");
    }
}
