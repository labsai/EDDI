/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import org.jboss.logging.Logger;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * The only way to cross orgs (plan §6.1).
 * <p>
 * Platform tables need no platform scope: their stores read no org and work in
 * any binding. {@code OrgScope.platform} is for cross-org access to org data:
 * claim queries across orgs, lookups by an unguessable id (an integration, an
 * OAuth state), migrations, integrity checks and platform admin views. Inside
 * it, {@link OrgContext#require()} throws, so per-item work needs a nested
 * {@link OrgContext#runAs}.
 * <p>
 * Every entry is logged at debug and counted per reason in
 * {@code eddi.org.platform.scope}. The build guard (plan §14.2) limits the
 * callers to an allow-list; at runtime any binding may enter it.
 */
public final class OrgScope {

    private static final Logger LOGGER = Logger.getLogger(OrgScope.class);

    private OrgScope() {
    }

    /**
     * Runs {@code work} in the platform plane.
     *
     * @param reason
     *            a short, fixed reason such as {@code "job:schedule-poller"}; it is
     *            a metric tag, so never include ids or user input
     */
    public static <T> T platform(String reason, Callable<T> work) throws Exception {
        Objects.requireNonNull(work, "work");
        requireReason(reason);
        if (OrgContext.isPlatform()) {
            return work.call();
        }
        LOGGER.debugf("Entering OrgScope.platform (%s)", reason);
        OrgContext.observer().platformScope(reason);
        return OrgContext.callUnder(new OrgContext.Platform(reason, OrgContext.nextEpoch()), work);
    }

    /**
     * {@link #platform} for work that returns nothing and throws no checked
     * exception.
     */
    public static void runPlatform(String reason, Runnable work) {
        Objects.requireNonNull(work, "work");
        try {
            platform(reason, () -> {
                work.run();
                return null;
            });
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            // unreachable: a Runnable throws no checked exception
            throw new IllegalStateException(e);
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("OrgScope.platform needs a reason");
        }
    }
}
