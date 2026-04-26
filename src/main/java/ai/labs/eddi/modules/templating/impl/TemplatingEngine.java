/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.quarkus.qute.Engine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
     * comment !}
     */
    private static final Pattern QUTE_CONTROL_PATTERN = Pattern.compile("\\{[a-zA-Z#/!]");

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
                var parsed = engine.parse(template);
                var instance = parsed.instance();
                if (dynamicAttributesMap != null) {
                    dynamicAttributesMap.forEach(instance::data);
                }
                return instance.render();
            } else {
                return template;
            }
        } catch (Exception e) {
            String message = "Error trying to insert context information into template. "
                    + "Either context is missing or reference in template is wrong!";
            throw new TemplateEngineException(message, e);
        }
    }

    private boolean containsTemplatingControlCharacters(String template) {
        return QUTE_CONTROL_PATTERN.matcher(template).find();
    }
}
