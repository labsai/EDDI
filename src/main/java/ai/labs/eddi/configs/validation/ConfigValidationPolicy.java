/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.validation;

import ai.labs.eddi.utils.LogSanitizer;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides what a save-time validator does with the problems it found: refuse
 * the save, or warn and let it through.
 * <p>
 * <b>Interactive saves</b> (REST, MCP, the Manager) are strict: the problems
 * become an {@link IllegalArgumentException}, which the API answers with a 400
 * naming the field. <b>Import, sync, upgrade and duplicate</b> move
 * configurations that were stored — or exported — by an earlier release, so a
 * check added later must not turn an archive that imported yesterday into a
 * refusal. Those callers run inside {@link #lenient()}: the same check then
 * logs a warning and records it, and the caller reads the recorded text back
 * with {@link Scope#warnings()} to put it in the import result.
 * <p>
 * The mode is per thread and per scope. An import, sync or upgrade request runs
 * on one thread from the archive to the last store write, which is why a plain
 * {@link ThreadLocal} is enough; work handed to another thread is not covered
 * and stays strict.
 */
public final class ConfigValidationPolicy {

    private static final Logger LOGGER = Logger.getLogger(ConfigValidationPolicy.class);

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private ConfigValidationPolicy() {
    }

    /**
     * Opens a lenient scope on this thread. Close it (try-with-resources) when the
     * import is over; scopes nest, and every enclosing scope keeps collecting.
     */
    public static Scope lenient() {
        Scope previous = CURRENT.get();
        Scope scope = new Scope(previous);
        CURRENT.set(scope);
        return scope;
    }

    /** Whether this thread is inside a {@link #lenient()} scope. */
    public static boolean isLenient() {
        return CURRENT.get() != null;
    }

    /**
     * Reports what a validator found. Strict: throws one
     * {@link IllegalArgumentException} listing every problem. Lenient: logs and
     * records each as a warning.
     *
     * @param what
     *            the kind of document, for the message ("LLM configuration")
     * @param problems
     *            one line per problem, each naming its field; may be empty
     */
    public static void report(String what, List<String> problems) {
        if (problems == null || problems.isEmpty()) {
            return;
        }
        Scope scope = CURRENT.get();
        if (scope == null) {
            throw new IllegalArgumentException("Invalid " + what + ": " + String.join("; ", problems));
        }
        for (String problem : problems) {
            String warning = what + ": " + problem;
            LOGGER.warnf("Config validation (warning only, import/upgrade): %s", LogSanitizer.sanitize(warning));
            scope.record(warning);
        }
    }

    /** A lenient scope; collects the warnings reported while it is open. */
    public static final class Scope implements AutoCloseable {
        private final Scope previous;
        private final List<String> warnings = new ArrayList<>();

        private Scope(Scope previous) {
            this.previous = previous;
        }

        private void record(String warning) {
            for (Scope scope = this; scope != null; scope = scope.previous) {
                if (!scope.warnings.contains(warning)) {
                    scope.warnings.add(warning);
                }
            }
        }

        /** What was reported inside this scope so far, in order. */
        public List<String> warnings() {
            return List.copyOf(warnings);
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
