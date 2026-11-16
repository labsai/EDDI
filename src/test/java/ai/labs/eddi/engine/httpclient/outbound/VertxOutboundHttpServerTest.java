/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Every server-backed guarantee of {@link OutboundHttpServerTest} — redirects,
 * SSRF on hops, deadlines, body caps — again on the Vert.x engine, so a
 * guarantee the facade promises cannot hold on one engine only.
 */
class VertxOutboundHttpServerTest extends OutboundHttpServerTest {

    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Override
    OutboundHttp createFacade() {
        return vertxFacade(vertx, OutboundSettings.defaults(Duration.ofSeconds(10)), false);
    }

    /** A facade with every profile on a Vert.x engine. */
    static OutboundHttp vertxFacade(Vertx vertx, OutboundSettings settings, boolean ssrfProtectionEnabled) {
        OutboundSettings onVertx = settings.onEngine(OutboundSettings.VERTX_ENGINE,
                OutboundSettings.DEFAULT_VERTX_MAX_CONNECTIONS_PER_HOST);
        return new OutboundHttp(onVertx,
                new VertxOutboundEngine(vertx, onVertx, VertxOutboundEngine.TlsOptions.NONE, UnaryOperator.identity()),
                ssrfProtectionEnabled, new SimpleMeterRegistry());
    }
}
