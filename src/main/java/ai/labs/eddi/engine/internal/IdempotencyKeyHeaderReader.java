/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.model.InputData;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Reads {@code Idempotency-Key} (or its alias {@code X-EDDI-Request-Id}) off
 * the active HTTP request and stamps it on the turn's {@link InputData}.
 * <p>
 * A separate bean for the same reason as {@link TurnDeadlineHeaderReader}: the
 * header must be read on the request thread, the turn runs later on a
 * coordinator thread.
 */
@ApplicationScoped
public class IdempotencyKeyHeaderReader {
    private static final Logger LOGGER = Logger.getLogger(IdempotencyKeyHeaderReader.class);

    /** The standard header. */
    public static final String HEADER = "Idempotency-Key";
    /** The alias, for callers that already carry a request id. */
    public static final String ALIAS_HEADER = "X-EDDI-Request-Id";

    private final CurrentVertxRequest currentVertxRequest;

    @Inject
    public IdempotencyKeyHeaderReader(CurrentVertxRequest currentVertxRequest) {
        this.currentVertxRequest = currentVertxRequest;
    }

    /**
     * Stamps the validated key on {@code inputData}; the standard header wins over
     * the alias. No header leaves it unset.
     *
     * @throws TurnIdempotencyService.InvalidIdempotencyKeyException
     *             when a key is present but malformed — the caller answers 400
     */
    public void apply(InputData inputData) {
        if (inputData == null) {
            return;
        }
        String raw = header(HEADER);
        if (raw == null) {
            raw = header(ALIAS_HEADER);
        }
        inputData.setIdempotencyKey(TurnIdempotencyService.validateKey(raw));
    }

    private String header(String name) {
        try {
            var current = currentVertxRequest.getCurrent();
            return current == null ? null : current.request().getHeader(name);
        } catch (RuntimeException e) {
            // No active request (a scheduled or internal caller): no header.
            LOGGER.debugf("No active request to read %s from: %s", name, e.getMessage());
            return null;
        }
    }
}
