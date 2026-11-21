/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The org the current code runs for (plan §6.1, ADR 0001 LOCK 3).
 * <p>
 * The binding is a {@link ScopedValue}, so it is visible to everything called
 * inside {@link #callAs} or {@link #runAs}, virtual threads included, and it
 * cannot leak onto the next task of a pooled thread: a missed hand-off fails
 * closed instead of serving a stale org. Every hand-off to another thread is
 * explicit, through {@link OrgPropagation}.
 * <p>
 * A binding is either an org or the platform ({@link OrgScope#platform}), and
 * each one gets a unique {@link #epoch()}, which the PostgreSQL layer uses to
 * prove that a connection never spans a change of binding. An epoch is unique
 * per binding, not per thread: {@link OrgPropagation} and forks that inherit
 * scoped values run the same binding, and so the same epoch, on several threads
 * at once. One epoch always means one org (or the platform plane), so a check
 * per physical connection stays sound. The allowed transitions:
 * <table>
 * <tr>
 * <th>From</th>
 * <th>To</th>
 * <th>Result</th>
 * </tr>
 * <tr>
 * <td>unbound</td>
 * <td>any</td>
 * <td>allowed</td>
 * </tr>
 * <tr>
 * <td>org X</td>
 * <td>org X</td>
 * <td>no-op</td>
 * </tr>
 * <tr>
 * <td>org X</td>
 * <td>org Y</td>
 * <td>{@link OrgBindingException}</td>
 * </tr>
 * <tr>
 * <td>platform</td>
 * <td>org</td>
 * <td>allowed (per-item processing)</td>
 * </tr>
 * <tr>
 * <td>org</td>
 * <td>platform</td>
 * <td>allowed; the build guard limits it to allow-listed classes</td>
 * </tr>
 * </table>
 * In {@link OrgMode#SINGLE} only {@link OrgId#DEFAULT} can be bound.
 */
public final class OrgContext {

    private static final Logger LOGGER = Logger.getLogger(OrgContext.class);

    /** A binding: an org, or the platform plane. */
    sealed interface Binding permits Org, Platform {
        long epoch();
    }

    record Org(OrgId org, long epoch) implements Binding {
    }

    record Platform(String reason, long epoch) implements Binding {
    }

    private static final ScopedValue<Binding> CURRENT = ScopedValue.newInstance();
    private static final AtomicLong EPOCHS = new AtomicLong();

    /**
     * Caps the distinct call sites remembered for the single-mode fallback warning.
     */
    static final int MAX_FALLBACK_SITES = 200;
    /**
     * The tag counted once the call-site or reason cap is reached; no real class or
     * reason looks like it.
     */
    static final String OVERFLOW_TAG = "(overflow)";
    private static final Set<String> WARNED_SITES = ConcurrentHashMap.newKeySet();
    private static final Set<String> INTERNAL_CLASSES = Set.of(OrgContext.class.getName(), OrgScope.class.getName(),
            OrgPropagation.class.getName(), OrgQuotaKeys.class.getName());

    /**
     * Read from the configuration when the class loads, not only when
     * {@link OrgConfig} applies it at startup. Code that runs before the
     * {@code StartupEvent} (runtime init, an eager bean) must already fail closed
     * in multi mode instead of falling back to the default org.
     */
    private static volatile OrgMode mode = initialMode();
    private static volatile OrgContextObserver observer = OrgContextObserver.NONE;

    private OrgContext() {
    }

    /**
     * The bound org.
     * <ul>
     * <li>Inside {@link OrgScope#platform}: throws. Org data needs a nested
     * {@link #runAs}.</li>
     * <li>Unbound in {@link OrgMode#MULTI}: throws.</li>
     * <li>Unbound in {@link OrgMode#SINGLE}: {@link OrgId#DEFAULT}, with a WARN
     * once per call site and a count in {@code eddi.org.context.fallback}. The
     * count is expected to stay 0.</li>
     * </ul>
     *
     * @throws OrgContextMissingException
     *             as described above
     */
    public static OrgId require() {
        Binding binding = bindingOrNull();
        if (binding instanceof Org org) {
            return org.org();
        }
        if (binding instanceof Platform) {
            throw new OrgContextMissingException("org data accessed inside OrgScope.platform without a nested OrgContext.runAs");
        }
        if (mode == OrgMode.MULTI) {
            throw new OrgContextMissingException("org data accessed without an org binding");
        }
        recordFallback();
        return OrgId.DEFAULT;
    }

    /** The bound org; empty when unbound or inside {@link OrgScope#platform}. */
    public static Optional<OrgId> current() {
        return bindingOrNull() instanceof Org org ? Optional.of(org.org()) : Optional.empty();
    }

    /**
     * True inside {@link OrgScope#platform} (and not inside a nested org binding).
     */
    public static boolean isPlatform() {
        return bindingOrNull() instanceof Platform;
    }

    /**
     * The current binding's epoch (unique per binding, possibly active on several
     * threads), or 0 when unbound.
     */
    public static long epoch() {
        Binding binding = bindingOrNull();
        return binding == null ? 0L : binding.epoch();
    }

    /** {@code eddi.org.mode} as applied at startup. */
    public static OrgMode mode() {
        return mode;
    }

    /**
     * Runs {@code work} bound to {@code org}, following the transition table above.
     * When {@code org} is already bound, {@code work} runs directly, under the same
     * epoch.
     */
    public static <T> T callAs(OrgId org, Callable<T> work) throws Exception {
        Objects.requireNonNull(work, "work");
        Binding next = transitionTo(org);
        if (next == null) {
            return work.call();
        }
        return ScopedValue.where(CURRENT, next).call(work::call);
    }

    /**
     * {@link #callAs} for work that returns nothing and throws no checked
     * exception.
     */
    public static void runAs(OrgId org, Runnable work) {
        Objects.requireNonNull(work, "work");
        Binding next = transitionTo(org);
        if (next == null) {
            work.run();
            return;
        }
        ScopedValue.where(CURRENT, next).run(work);
    }

    /**
     * The binding to enter for {@code org}, or {@code null} when it is already
     * bound.
     */
    private static Binding transitionTo(OrgId org) {
        Objects.requireNonNull(org, "org");
        if (mode == OrgMode.SINGLE && !org.isDefault()) {
            throw new OrgBindingException("only the default org can be bound in single mode");
        }
        Binding current = bindingOrNull();
        if (current instanceof Org bound) {
            if (bound.org().equals(org)) {
                return null;
            }
            throw new OrgBindingException("a different org is already bound; crossing orgs is not allowed");
        }
        return new Org(org, nextEpoch());
    }

    // ------------------------------------------------------------ package API

    static Binding bindingOrNull() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    /** Runs {@code work} under exactly {@code binding} ({@code null} = unbound). */
    static <T> T callUnder(Binding binding, Callable<T> work) throws Exception {
        return ScopedValue.where(CURRENT, binding).call(work::call);
    }

    /**
     * {@code eddi.org.mode} as the configuration holds it at class load. Any value
     * other than {@code multi}, and a configuration that cannot be read, start as
     * {@link OrgMode#SINGLE}; {@link OrgConfig} then validates the value and
     * refuses a bad one at startup.
     */
    static OrgMode initialMode() {
        try {
            String value = ConfigProvider.getConfig().getOptionalValue("eddi.org.mode", String.class).orElse("single");
            return "multi".equals(value.trim().toLowerCase(Locale.ROOT)) ? OrgMode.MULTI : OrgMode.SINGLE;
        } catch (RuntimeException e) {
            return OrgMode.SINGLE;
        }
    }

    static long nextEpoch() {
        return EPOCHS.incrementAndGet();
    }

    static OrgContextObserver observer() {
        return observer;
    }

    static void configure(OrgMode newMode, OrgContextObserver newObserver) {
        mode = Objects.requireNonNull(newMode, "mode");
        observer = newObserver == null ? OrgContextObserver.NONE : newObserver;
    }

    /** Test support: forgets the call sites already warned about. */
    static void resetWarnings() {
        WARNED_SITES.clear();
    }

    /** Test support: the call sites already warned about. */
    static Set<String> warnedSites() {
        return WARNED_SITES;
    }

    private static void recordFallback() {
        String site = callSite();
        boolean newSite = !WARNED_SITES.contains(site) && WARNED_SITES.size() < MAX_FALLBACK_SITES && WARNED_SITES.add(site);
        if (newSite) {
            LOGGER.warnf("[SECURITY] org data accessed without an org binding at %s; using the default org. "
                    + "This is harmless in single mode but must be fixed before multi mode.", site);
        }
        observer.fallback(WARNED_SITES.contains(site) ? site : OVERFLOW_TAG);
    }

    private static String callSite() {
        return StackWalker.getInstance().walk(frames -> frames.filter(f -> !INTERNAL_CLASSES.contains(f.getClassName())).findFirst()
                .map(f -> f.getClassName() + "#" + f.getMethodName()).orElse("unknown"));
    }
}
