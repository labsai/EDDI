/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The one bound every paginated listing applies to its {@code index} and
 * {@code limit} query parameters.
 * <p>
 * Each list endpoint used to pass {@code limit} to its store as it came, so
 * {@code ?limit=2147483647} returned every descriptor in the deployment in one
 * response (L10 of the 2026-10-15 security review, verified live against
 * {@code /agentstore/agents/descriptors}). A page is a page: a caller that
 * wants more walks {@code index}. The ceiling is {@value #MAX_LIMIT_PROPERTY}
 * (default {@value #DEFAULT_MAX_LIMIT}) and a request above it — or with a
 * negative {@code index} — is refused with 400 rather than silently clamped, so
 * a client that asked for more than it can get learns so instead of mistaking a
 * truncated page for the whole.
 * <p>
 * Static on purpose: {@code RestVersionInfo} is built with {@code new} by
 * seventeen stores and has no injection point, and a bound that differs by
 * surface is what let this one slip. The property is read once and cached; it
 * is deployment configuration, not something that changes at runtime.
 */
public final class PageBounds {

    public static final String MAX_LIMIT_PROPERTY = "eddi.rest.listing.max-limit";
    public static final int DEFAULT_MAX_LIMIT = 200;

    private static volatile Integer maxLimit;

    private PageBounds() {
    }

    /** The largest {@code limit} any listing accepts. */
    public static int maxLimit() {
        Integer value = maxLimit;
        if (value == null) {
            value = readConfiguredMaxLimit();
            maxLimit = value;
        }
        return value;
    }

    private static int readConfiguredMaxLimit() {
        try {
            int configured = ConfigProvider.getConfig().getOptionalValue(MAX_LIMIT_PROPERTY, Integer.class).orElse(DEFAULT_MAX_LIMIT);
            return configured > 0 ? configured : DEFAULT_MAX_LIMIT;
        } catch (RuntimeException | NoClassDefFoundError e) {
            // No config source on this classpath (plain unit tests): the default.
            return DEFAULT_MAX_LIMIT;
        }
    }

    /**
     * Pins the ceiling, or with {@code null} goes back to reading it from the
     * configuration. For tests.
     */
    static void overrideMaxLimit(Integer value) {
        maxLimit = value;
    }

    /**
     * Refuses a negative {@code index} or a {@code limit} above {@link #maxLimit()}
     * with 400. {@code null} is left alone — the endpoint's own default applies —
     * and so is a non-positive {@code limit}, which each store already normalises.
     */
    public static void require(Integer index, Integer limit) {
        if (index != null && index < 0) {
            throw new BadRequestException("'index' must not be negative");
        }
        requireLimit(limit);
    }

    /** As {@link #require(Integer, Integer)}, for an endpoint without an index. */
    public static void requireLimit(Integer limit) {
        int max = maxLimit();
        if (limit != null && limit > max) {
            throw new BadRequestException("'limit' must be at most " + max + "; page through the results with 'index' instead");
        }
    }
}
