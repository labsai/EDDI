/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;

/**
 * A static, parse-only view of the runtime template engine — for the places
 * that must decide whether an author-written template is acceptable
 * <em>before</em> it is stored (config save, ZIP import, migration), without
 * having a CDI-managed {@code TemplatingEngine} at hand.
 * <p>
 * The engine is {@link RuntimeTemplateEngineFactory} built from a plain Qute
 * engine with its default settings. What it adds at parse level is the section
 * set of the runtime engine ({@code if}, {@code for}/{@code each},
 * {@code with}, {@code let}/{@code set}, {@code when}/{@code switch}; no
 * includes, no user tags) and its parser settings, so a template passes the
 * check exactly when the runtime can read it. Namespaces are resolved at render
 * time, so an unknown one such as {@code vars:} or {@code json:} does not fail
 * a parse.
 */
public final class RuntimeTemplateParseCheck {

    private static final Engine PARSE_ENGINE = RuntimeTemplateEngineFactory.build(Engine.builder().addDefaults().build(),
            RuntimeTemplateEngineFactory.Settings.defaults());

    private RuntimeTemplateParseCheck() {
    }

    /**
     * Why {@code template} is not valid for the runtime engine, or {@code null}
     * when it parses. Parse only, nothing is rendered. A {@code null} template
     * parses.
     */
    public static String parseError(String template) {
        if (template == null) {
            return null;
        }
        try {
            PARSE_ENGINE.parse(template);
            return null;
        } catch (StackOverflowError e) {
            return "the template nests its sections too deeply to parse";
        } catch (RuntimeException e) {
            String message = e.getMessage();
            return message == null ? e.getClass().getSimpleName() : message.replaceAll("\\s+", " ").trim();
        }
    }

    /**
     * Parses {@code template} with the runtime engine. For callers that inspect the
     * parsed expressions ({@link Template#getExpressions()}).
     *
     * @throws RuntimeException
     *             when the template does not parse; use {@link #parseError} first
     *             for a message
     */
    public static Template parse(String template) {
        return PARSE_ENGINE.parse(template);
    }
}
