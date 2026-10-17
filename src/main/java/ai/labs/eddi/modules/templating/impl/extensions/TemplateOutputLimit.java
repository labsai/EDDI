/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl.extensions;

import ai.labs.eddi.modules.templating.impl.RuntimeTemplateEngineFactory.TemplateLimitExceededException;

/**
 * The {@code eddi.templating.max-output-chars} bound as the string template
 * extensions see it.
 * <p>
 * Every value a template expression resolves to is checked against that bound —
 * but only AFTER the extension method has returned it. {@code str.replace} and
 * {@code str.concat} allocate their result first, so a template could make them
 * build a string far past the bound, and have it refused only once the memory
 * was spent. The extensions now ask here, with the size they are about to
 * produce, before they allocate.
 * <p>
 * Qute calls extension methods statically, so the bound cannot travel with the
 * engine instance. It is a process-wide value the production
 * {@code TemplatingEngine} sets from its configuration at startup; until then,
 * and in a unit test, it is the shipped default. {@code 0} or less disables it,
 * as it does for the render itself.
 */
public final class TemplateOutputLimit {
    public static final String PROPERTY = "eddi.templating.max-output-chars";
    public static final int DEFAULT_MAX_CHARS = 2_000_000;

    private static volatile int maxChars = DEFAULT_MAX_CHARS;

    private TemplateOutputLimit() {
    }

    /**
     * Sets the bound; the production engine calls this with its configured value.
     */
    public static void configure(int maxOutputChars) {
        maxChars = maxOutputChars;
    }

    public static int maxChars() {
        return maxChars;
    }

    /**
     * Refuses an operation that is about to produce {@code expectedChars}
     * characters when that is over the bound.
     *
     * @throws TemplateLimitExceededException
     *             the same failure a too-long rendered value raises
     */
    public static void requireWithin(long expectedChars, String operation) {
        int limit = maxChars;
        if (limit > 0 && expectedChars > limit) {
            throw new TemplateLimitExceededException("str." + operation + " would produce " + expectedChars
                    + " characters, more than the limit of " + limit + " (" + PROPERTY + ")");
        }
    }
}
