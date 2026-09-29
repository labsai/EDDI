/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.bootstrap;

import ai.labs.eddi.engine.httpclient.impl.VertxHttpClient;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.RequestOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        // Named as a string rather than jakarta.ws.rs.Produces.class on purpose: the
        // simple name is already taken by the CDI annotation imported above, and an
        // inline FQN is what ImportStyleTest (AGENTS.md 4.7) exists to stop. Matching
        // on the annotation type's name keeps the assertion exact without either an
        // allowlist entry or a second import of the same simple name.
        assertTrue(Arrays.stream(producer.getAnnotations())
                .noneMatch(a -> "jakarta.ws.rs.Produces".equals(a.annotationType().getName())),
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

    /**
     * A Vertx whose blocking section runs inline, so the veto is observable
     * synchronously.
     */
    @SuppressWarnings("unchecked")
    private static Vertx inlineBlockingVertx() {
        Vertx vertx = mock(Vertx.class);
        when(vertx.executeBlocking(any(Callable.class), anyBoolean())).thenAnswer(invocation -> {
            try {
                return Future.succeededFuture(((Callable<Object>) invocation.getArgument(0)).call());
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        });
        return vertx;
    }

    @ParameterizedTest(name = "a hop to {0} is refused")
    @ValueSource(strings = {"169.254.169.254", "169.254.170.2", "fd00:ec2::254", "[fd00:ec2::254]", "metadata.google.internal"})
    @DisplayName("a redirect hop to the metadata service or the link-local range is refused")
    void metadataHopIsRefused(String host) {
        assertThrows(IllegalArgumentException.class, () -> HttpClientModule.requireNotMetadataHop(new RequestOptions().setHost(host)));
    }

    @Test
    @DisplayName("an ordinary hop is followed unchanged")
    void ordinaryHopIsFollowed() {
        RequestOptions ordinary = new RequestOptions().setHost("93.184.216.34");

        assertSame(ordinary, HttpClientModule.requireNotMetadataHop(ordinary));
    }

    @Test
    @DisplayName("a response the default handler does not follow stays unfollowed")
    void unfollowedResponseStaysUnfollowed() {
        Function<HttpClientResponse, Future<RequestOptions>> notFollowed = response -> null;

        assertNull(HttpClientModule.refusingMetadataHops(inlineBlockingVertx(), notFollowed).apply(mock(HttpClientResponse.class)));
    }

    @Test
    @DisplayName("the produced client vetoes every redirect hop, not only the first target the call validated")
    @SuppressWarnings("unchecked")
    void producedClientRefusesARedirectToTheMetadataService() {
        Vertx vertx = inlineBlockingVertx();
        HttpClient httpClient = mock(HttpClient.class);
        HttpClientOptions options = new HttpClientOptions();
        when(vertx.createHttpClient(options)).thenReturn(httpClient);
        // What the default handler returns for "302 Location:
        // http://169.254.169.254/latest/meta-data/".
        Function<HttpClientResponse, Future<RequestOptions>> followsToMetadata = response -> Future
                .succeededFuture(new RequestOptions().setHost("169.254.169.254").setURI("/latest/meta-data/"));
        when(httpClient.redirectHandler()).thenReturn(followsToMetadata);

        // The client provideHttpClient wraps into its WebClient.
        assertSame(httpClient, HttpClientModule.guardedHttpClient(vertx, options));

        ArgumentCaptor<Function<HttpClientResponse, Future<RequestOptions>>> installed = ArgumentCaptor.forClass(Function.class);
        verify(httpClient).redirectHandler(installed.capture());
        Future<RequestOptions> hop = installed.getValue().apply(mock(HttpClientResponse.class));
        assertTrue(hop.failed(), "the redirect must not be followed");
        assertInstanceOf(IllegalArgumentException.class, hop.cause());
    }

    private static HttpClientRequest requestFrom(String absoluteUri, String host, int port) {
        HttpClientRequest request = mock(HttpClientRequest.class);
        when(request.absoluteURI()).thenReturn(absoluteUri);
        when(request.getHost()).thenReturn(host);
        when(request.getPort()).thenReturn(port);
        return request;
    }

    private static RequestOptions hopTo(String host, Integer port, Boolean ssl) {
        RequestOptions options = new RequestOptions().setHost(host).setPort(port).setSsl(ssl).setURI("/next");
        options.addHeader("Authorization", "Bearer live-token");
        options.addHeader("X-Api-Key", "secret-key");
        options.addHeader("Cookie", "session=abc");
        options.addHeader("Accept", "application/json");
        return options;
    }

    @Test
    @DisplayName("a cross-origin redirect hop is stripped of every credential header, keeping plain ones")
    void crossOriginHopStripsCredentials() {
        HttpClientRequest original = requestFrom("https://api.example.com/thing", "api.example.com", 443);
        RequestOptions next = hopTo("evil.example.net", 443, true);

        HttpClientModule.stripCredentialsIfCrossOrigin(original, next);

        assertNull(next.getHeaders().get("Authorization"), "Authorization must not be replayed to another host");
        assertNull(next.getHeaders().get("X-Api-Key"), "a custom credential header must not be replayed to another host");
        assertNull(next.getHeaders().get("Cookie"), "Cookie must not be replayed to another host");
        assertTrue(next.getHeaders().contains("Accept"), "a non-credential header stays");
    }

    @Test
    @DisplayName("a same-origin redirect hop keeps its credential headers so an in-service redirect still authenticates")
    void sameOriginHopKeepsCredentials() {
        HttpClientRequest original = requestFrom("https://api.example.com/thing", "api.example.com", 443);
        RequestOptions next = hopTo("api.example.com", 443, true);

        HttpClientModule.stripCredentialsIfCrossOrigin(original, next);

        assertTrue(next.getHeaders().contains("Authorization"), "same-origin hop keeps Authorization");
        assertTrue(next.getHeaders().contains("X-Api-Key"), "same-origin hop keeps the custom credential header");
    }

    @Test
    @DisplayName("a hop to a different port on the same host is cross-origin and is stripped")
    void differentPortIsCrossOrigin() {
        HttpClientRequest original = requestFrom("https://api.example.com/thing", "api.example.com", 443);
        RequestOptions next = hopTo("api.example.com", 8443, true);

        HttpClientModule.stripCredentialsIfCrossOrigin(original, next);

        assertNull(next.getHeaders().get("Authorization"), "a different port is a different origin");
    }

    @Test
    @DisplayName("when the originating request is unknown, the hop is stripped fail-safe")
    void nullOriginalStrips() {
        RequestOptions next = hopTo("api.example.com", 443, true);

        HttpClientModule.stripCredentialsIfCrossOrigin(null, next);

        assertNull(next.getHeaders().get("Authorization"), "with no origin to compare, fail safe and strip");
    }

    @Test
    @DisplayName("the wrapper strips a cross-origin hop end to end")
    void wrapperStripsCrossOriginHop() {
        HttpClientRequest originalRequest = requestFrom("https://api.example.com/thing", "api.example.com", 443);
        HttpClientResponse response = mock(HttpClientResponse.class);
        when(response.request()).thenReturn(originalRequest);
        Function<HttpClientResponse, Future<RequestOptions>> followsCrossOrigin = r -> Future.succeededFuture(hopTo("other.example.net", 443, true));

        Future<RequestOptions> hop = HttpClientModule.strippingCrossOriginCredentials(followsCrossOrigin).apply(response);

        assertTrue(hop.succeeded());
        assertNull(hop.result().getHeaders().get("Authorization"));
        assertNull(hop.result().getHeaders().get("X-Api-Key"));
        assertTrue(hop.result().getHeaders().contains("Accept"));
    }

    @Test
    @DisplayName("the wrapper leaves a not-followed response unfollowed")
    void wrapperPassesThroughNull() {
        Function<HttpClientResponse, Future<RequestOptions>> notFollowed = r -> null;

        assertNull(HttpClientModule.strippingCrossOriginCredentials(notFollowed).apply(mock(HttpClientResponse.class)));
    }
}
