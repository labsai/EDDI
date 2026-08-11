/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Qute-based implementation of the EDDI templating engine. Replaces the
 * previous Thymeleaf implementation for native image compatibility.
 *
 * @author ginccc
 */
@ApplicationScoped
public class TemplatingEngine implements ITemplatingEngine {

    /**
     * Matches Qute control characters: {variable}, {#for}, {#if}, {/for}, {!
     * comment !}, {|unparsed|}
     * <p>
     * The unparsed-block opener <code>{|</code> has to be on this list even though
     * it introduces no <em>evaluation</em>: it is still syntax the renderer
     * consumes, so a template whose ONLY marker was an unparsed block skipped
     * processing entirely and shipped its literal <code>{|…|&#125;</code>
     * delimiters onward — visibly, into a system prompt. That made the escape work
     * only by luck: it stripped cleanly whenever something else in the same
     * template happened to trigger a render, and leaked whenever nothing did. The
     * setup wizard concatenates a generated endpoint summary that may legitimately
     * contain no other marker at all — see
     * {@link ai.labs.eddi.modules.templating.TemplateEscaping}.
     */
    private static final Pattern QUTE_CONTROL_PATTERN = Pattern.compile("\\{[a-zA-Z#/!|]");

    /**
     * Upper bound for the recursive escaping of the template data model. Guards
     * against a pathological (or cyclic) structure blowing the stack.
     */
    private static final int MAX_ESCAPE_DEPTH = 20;

    /**
     * Compiled templates, keyed on the template string itself. Templates are
     * immutable and thread-safe once parsed — {@link Template#instance()} creates
     * the per-render state — so the same compiled template is reused across turns
     * and conversations.
     * <p>
     * The cache is deliberately BOUNDED: the keys are agent-authored template
     * strings, which for httpcall bodies and property instructions can embed
     * per-turn content, so an unbounded cache would be a slow memory leak. Sizing
     * follows the sibling factories in {@code modules/llm/impl} (bounded size +
     * idle expiry).
     */
    private final Cache<String, Template> compiledTemplates = Caffeine.newBuilder().maximumSize(1000)
            .expireAfterAccess(Duration.ofMinutes(30)).build();

    private final Engine engine;

    @Inject
    public TemplatingEngine(Engine engine) {
        this.engine = engine;
    }

    @Override
    public String processTemplate(String template, Map<String, Object> dynamicAttributesMap) throws TemplateEngineException {
        return processTemplate(template, dynamicAttributesMap, TemplateMode.TEXT);
    }

    @Override
    public String processTemplate(String template, Map<String, Object> dynamicAttributesMap, TemplateMode templateMode)
            throws TemplateEngineException {
        try {
            if (template == null || template.isEmpty()) {
                return template;
            }
            if (containsTemplatingControlCharacters(template)) {
                var parsed = compiledTemplates.get(template, engine::parse);
                var instance = parsed.instance();
                var escapedAttributes = escapeAttributes(dynamicAttributesMap, templateMode);
                if (escapedAttributes != null) {
                    escapedAttributes.forEach(instance::data);
                }
                return instance.render();
            } else {
                return template;
            }
        } catch (Exception e) {
            String preview = template.length() > 200 ? template.substring(0, 200) + "…" : template;
            String cause = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            String message = "Template rendering failed: " + cause
                    + " | Template preview: " + preview;
            throw new TemplateEngineException(message, e);
        }
    }

    private boolean containsTemplatingControlCharacters(String template) {
        return QUTE_CONTROL_PATTERN.matcher(template).find();
    }

    /**
     * Honours {@link TemplateMode} by escaping the <em>data</em> that is about to
     * be substituted into the template — the static template text itself is left
     * alone, which is what makes HTML mode useful (markup in the template stays
     * markup, values coming from conversation memory cannot inject any).
     * <p>
     * Escaping the data rather than relying on Qute's {@code HtmlEscaper} result
     * mapper keeps the guarantee self-contained: the injected {@link Engine} is
     * configured by Quarkus (see {@code quarkus.qute.escape-content-types}) and a
     * security-relevant escape must not depend on external configuration.
     * <p>
     * Covers the EDDI template data model (nested maps, collections and strings —
     * see {@code MemoryItemConverter}). Fields reached by reflection on arbitrary
     * POJOs cannot be intercepted and are left as-is.
     */
    private static Map<String, Object> escapeAttributes(Map<String, Object> dynamicAttributesMap, TemplateMode templateMode) {
        if (dynamicAttributesMap == null || templateMode == null || templateMode == TemplateMode.TEXT) {
            return dynamicAttributesMap;
        }

        var escaped = new LinkedHashMap<String, Object>();
        dynamicAttributesMap.forEach((key, value) -> escaped.put(key, escapeValue(value, templateMode, 1)));
        return escaped;
    }

    private static Object escapeValue(Object value, TemplateMode templateMode, int depth) {
        if (value instanceof String stringValue) {
            return escape(stringValue, templateMode);
        }
        if (depth >= MAX_ESCAPE_DEPTH) {
            return value;
        }
        if (value instanceof Map<?, ?> mapValue) {
            var escaped = new LinkedHashMap<Object, Object>();
            mapValue.forEach((key, nested) -> escaped.put(key, escapeValue(nested, templateMode, depth + 1)));
            return escaped;
        }
        if (value instanceof Collection<?> collectionValue) {
            var escaped = new ArrayList<>(collectionValue.size());
            collectionValue.forEach(nested -> escaped.add(escapeValue(nested, templateMode, depth + 1)));
            return escaped;
        }
        return value;
    }

    private static String escape(String value, TemplateMode templateMode) {
        return switch (templateMode) {
            case HTML -> escapeHtml(value);
            case JAVASCRIPT -> escapeJavaScript(value);
            case TEXT -> value;
        };
    }

    /**
     * Same replacement set as Qute's {@code HtmlEscaper}, so HTML-mode output is
     * indistinguishable from natively escaped Qute output.
     */
    private static String escapeHtml(String value) {
        StringBuilder escaped = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String replacement = switch (c) {
                case '"' -> "&quot;";
                case '\'' -> "&#39;";
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                default -> null;
            };

            if (replacement == null) {
                if (escaped != null) {
                    escaped.append(c);
                }
            } else {
                if (escaped == null) {
                    escaped = new StringBuilder(value.length() + 16);
                    escaped.append(value, 0, i);
                }
                escaped.append(replacement);
            }
        }

        return escaped == null ? value : escaped.toString();
    }

    /**
     * Conservative JavaScript string-literal escaping (OWASP): everything that is
     * not alphanumeric is hex-encoded, so a value can never break out of the
     * literal it is substituted into, whichever quoting style the template uses.
     */
    private static String escapeJavaScript(String value) {
        var escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                escaped.append(c);
            } else if (c < 256) {
                escaped.append(String.format("\\x%02X", (int) c));
            } else {
                escaped.append(String.format("\\u%04X", (int) c));
            }
        }

        return escaped.toString();
    }
}
