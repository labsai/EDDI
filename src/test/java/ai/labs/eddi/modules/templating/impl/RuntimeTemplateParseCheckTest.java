/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import io.quarkus.qute.Expression;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeTemplateParseCheckTest {

    @ParameterizedTest
    @ValueSource(strings = {"plain text", "{properties.x}", "{#for s in properties.list}{s.title}\n{/for}",
            "{#if a == 'b'}x{/if}", "{vars:thing}", "{json:encode(properties.x)}"})
    void validTemplatesParse(String template) {
        assertNull(RuntimeTemplateParseCheck.parseError(template));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{#for s in}", "{#if x}never closed", "{#include foo /}", "{#unknownsection}x{/unknownsection}"})
    void invalidTemplatesAreRefusedWithAMessage(String template) {
        String error = RuntimeTemplateParseCheck.parseError(template);
        assertNotNull(error, template);
        assertFalse(error.isBlank());
        assertFalse(error.contains("\n"), "message is single-line");
    }

    @Test
    void nullParses() {
        assertNull(RuntimeTemplateParseCheck.parseError(null));
    }

    @Test
    void migratorDelegatesAndAgrees() {
        TemplateSyntaxMigrator migrator = new TemplateSyntaxMigrator();
        for (String t : List.of("{properties.x}", "{#for s in}", "{#include foo /}", "plain")) {
            assertEquals(RuntimeTemplateParseCheck.parseError(t), migrator.quteParseError(t), t);
        }
    }

    @Test
    void parsedTemplateExposesExpressionsAndNamespaces() {
        var template = RuntimeTemplateParseCheck.parse("{vault:secret} {#for s in call.args.list}{s.x}{/for}");
        List<Expression> expressions = template.getExpressions();
        assertTrue(expressions.stream().anyMatch(e -> "vault".equals(e.getNamespace())));
        assertTrue(expressions.stream().anyMatch(e -> e.getNamespace() == null && !e.getParts().isEmpty()
                && "call".equals(e.getParts().get(0).getName())));
    }
}
