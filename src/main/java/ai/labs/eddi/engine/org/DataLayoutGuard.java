/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Refuses to start a binary on data it does not understand (plan §5.4, ADR 0001
 * DV-11).
 * <p>
 * The org enablement raises {@code layout.required} as it changes the shared
 * data layout. A binary whose {@link #SUPPORTED_LAYOUT} is lower would read
 * that data unfiltered (on MongoDB) or with the wrong unique keys, so it must
 * not start. This class makes the first release that contains it the <b>floor
 * release</b>: every later binary refuses a layout it does not support. A
 * binary older than the floor has no guard, so rolling back below the floor
 * stays forbidden.
 * <p>
 * It observes {@code StartupEvent} at {@code Integer.MIN_VALUE}, a priority
 * reserved for it, so it runs before every other startup observer and before
 * the HTTP listener opens (Spike C). It only reads:
 * {@link IOrgMarkerStore#read()} creates nothing.
 * <p>
 * If the record cannot be read (the database is unreachable, or refuses the
 * read), it retries until {@code eddi.org.layout-guard.wait} has passed and
 * then refuses to start: it fails closed rather than run a binary unchecked
 * against a layout it may not support. A record that is read but cannot be
 * interpreted (an unknown marker state, a level that is not a whole number) is
 * refused at once.
 */
@ApplicationScoped
public class DataLayoutGuard {

    private static final Logger LOGGER = Logger.getLogger(DataLayoutGuard.class);

    /**
     * The highest {@code layout.required} this binary supports. 0 is today's
     * layout; the release that implements the full enablement (plan §5.3) raises it
     * to 1 ({@code org-layout-1}).
     */
    public static final int SUPPORTED_LAYOUT = 0;

    static final long FIRST_BACKOFF_MS = 500;
    static final long MAX_BACKOFF_MS = 5_000;
    /** Keeps the deadline arithmetic in range for any configured wait. */
    static final Duration MAX_WAIT = Duration.ofDays(365);

    /** Sleeps between attempts; replaced in tests. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final IOrgMarkerStore store;
    private final Duration wait;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    @Inject
    public DataLayoutGuard(IOrgMarkerStore store, @ConfigProperty(name = "eddi.org.layout-guard.wait", defaultValue = "PT60S") Duration wait) {
        this(store, wait, System::nanoTime, Thread::sleep);
    }

    DataLayoutGuard(IOrgMarkerStore store, Duration wait, LongSupplier nanoTime, Sleeper sleeper) {
        this.store = Objects.requireNonNull(store, "store");
        this.wait = wait == null || wait.isNegative() ? Duration.ZERO : wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    void onStart(@Observes
    @Priority(Integer.MIN_VALUE) StartupEvent event) {
        check();
    }

    /**
     * Reads the layout record and refuses an unsupported one.
     *
     * @return the record that was read
     * @throws IllegalStateException
     *             when the layout is not supported, or the record could not be read
     *             within {@code eddi.org.layout-guard.wait}; either stops startup
     */
    OrgMarker check() {
        OrgMarker marker = readWithin();
        if (marker.requiredLayout() > SUPPORTED_LAYOUT) {
            throw new IllegalStateException("[SECURITY] The data layout requires level " + marker.requiredLayout()
                    + ", but this EDDI binary supports up to level " + SUPPORTED_LAYOUT + ". Start a binary that supports it. "
                    + "Rolling back below the release that raised the layout is not supported (see docs/adr/0001-organizations.md).");
        }
        LOGGER.debugf("Data layout check passed: layout.required=%d, supported=%d, marker=%s", marker.requiredLayout(), SUPPORTED_LAYOUT,
                marker.state());
        return marker;
    }

    private OrgMarker readWithin() {
        long deadline = nanoTime.getAsLong() + wait.toNanos();
        long backoff = FIRST_BACKOFF_MS;
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return store.read();
            } catch (UnsupportedLayoutRecordException e) {
                // read, but not interpretable: not an outage, so retrying would only delay the
                // refusal
                throw new IllegalStateException("[SECURITY] The data layout record cannot be interpreted by this EDDI binary ("
                        + e.getMessage() + "). Start a binary that supports it.", e);
            } catch (RuntimeException e) {
                long remainingMs = (deadline - nanoTime.getAsLong()) / 1_000_000;
                if (remainingMs <= 0) {
                    throw new IllegalStateException("[SECURITY] Could not read the data layout record after " + attempt
                            + " attempt(s) within eddi.org.layout-guard.wait=" + wait + ". Refusing to start, because this binary cannot "
                            + "confirm it supports the stored layout. Check the database connection.", e);
                }
                LOGGER.warnf("Could not read the data layout record (attempt %d), retrying: %s", attempt, e.getMessage());
                try {
                    sleeper.sleep(Math.min(backoff, remainingMs));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("[SECURITY] Interrupted while waiting to read the data layout record", interrupted);
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }
}
