/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.hitl.audit.HitlChannel;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Reads {@code X-EDDI-Client} off the active HTTP request: the channel a REST
 * HITL decision records ({@code MANAGER} for {@code manager}, {@code REST}
 * otherwise). The header is the client's claim — an analytics hint, never used
 * for authorization.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class HitlClientChannelReader {
    private static final Logger LOGGER = Logger.getLogger(HitlClientChannelReader.class);

    private final CurrentVertxRequest currentVertxRequest;

    @Inject
    public HitlClientChannelReader(CurrentVertxRequest currentVertxRequest) {
        this.currentVertxRequest = currentVertxRequest;
    }

    /** The channel of the current request; {@code REST} without one. */
    public HitlChannel current() {
        try {
            var current = currentVertxRequest.getCurrent();
            return HitlChannel.fromClientHeader(current == null ? null : current.request().getHeader(HitlChannel.CLIENT_HEADER));
        } catch (RuntimeException e) {
            LOGGER.debugf("No active request to read %s from: %s", HitlChannel.CLIENT_HEADER, e.getMessage());
            return HitlChannel.REST;
        }
    }

    /** {@link #current()} through a reader that may be absent (unit tests). */
    public static HitlChannel of(HitlClientChannelReader reader) {
        return reader != null ? reader.current() : HitlChannel.REST;
    }
}
