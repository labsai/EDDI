/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import ai.labs.eddi.modules.templating.ITemplatingEngine.TemplateMode;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comprehensive tests for the Qute-based TemplatingEngine.
 *
 * @author ginccc
 */
public class TemplatingEngineTest {
    private ITemplatingEngine templatingEngine;

    @BeforeEach
    public void setUp() {
        Engine quteEngine = Engine.builder().addDefaults().strictRendering(false).build();
        templatingEngine = new TemplatingEngine(quteEngine);
    }

    // --- Basic variable substitution ---

    @Test
    public void processTemplateWithStringContext() throws Exception {
        String template = "Some kind of string having a {value}";
        var data = Map.<String, Object>of("value", "testValue");

        String result = templatingEngine.processTemplate(template, data);

        Assertions.assertEquals("Some kind of string having a testValue", result);
    }

    @Test
    public void processTemplateWithObjectContext() throws Exception {
        String template = "Some kind of string having a {obj.value}";
        var data = Map.<String, Object>of("obj", Map.of("value", "testValue"));

        String result = templatingEngine.processTemplate(template, data);

        Assertions.assertEquals("Some kind of string having a testValue", result);
    }

    @Test
    public void processTemplateWithMapNavigation() throws Exception {
        String template = "Hello {memory.input}";
        var memory = Map.<String, Object>of("input", "world");
        var data = Map.<String, Object>of("memory", memory);

        String result = templatingEngine.processTemplate(template, data);

        Assertions.assertEquals("Hello world", result);
    }

    @Test
    public void processTemplateWithNestedMaps() throws Exception {
        String template = "City: {properties.address.city}";
        var address = Map.<String, Object>of("city", "Vienna");
        var properties = Map.<String, Object>of("address", address);
        var data = Map.<String, Object>of("properties", properties);

        String result = templatingEngine.processTemplate(template, data);

        Assertions.assertEquals("City: Vienna", result);
    }

    // --- No-op cases ---

    @Test
    public void processTemplateWithNoControlChars() throws Exception {
        String template = "Plain text without any template syntax";
        var data = Map.<String, Object>of("value", "ignored");

        String result = templatingEngine.processTemplate(template, data);

        Assertions.assertEquals("Plain text without any template syntax", result);
    }

    @Test
    public void processTemplateWithJsonContent() throws Exception {
        // JSON curly braces should NOT trigger template processing
        String template = "{\"key\": \"value\"}";
        var data = Map.<String, Object>of();

        String result = templatingEngine.processTemplate(template, data);

        Assertions.assertEquals("{\"key\": \"value\"}", result);
    }

    @Test
    public void processTemplateWithNullTemplate() throws Exception {
        String result = templatingEngine.processTemplate(null, Map.of());
        Assertions.assertNull(result);
    }

    @Test
    public void processTemplateWithEmptyTemplate() throws Exception {
        String result = templatingEngine.processTemplate("", Map.of());
        Assertions.assertEquals("", result);
    }

    @Test
    public void processTemplateWithNullMap() throws Exception {
        String template = "Hello {name}";
        // Should not throw NPE
        String result = templatingEngine.processTemplate(template, null);
        Assertions.assertNotNull(result);
    }

    // --- Iteration ---

    @Test
    public void processTemplateWithIteration() throws Exception {
        String template = "{#for item in items}{item}{#if item_hasNext},{/if}{/for}";
        var data = Map.<String, Object>of("items", List.of("a", "b", "c"));

        String result = templatingEngine.processTemplate(template, data);

        Assertions.assertEquals("a,b,c", result);
    }

    @Test
    public void processTemplateWithIterationCount() throws Exception {
        String template = "{#for item in items}{item_count}{/for}";
        var data = Map.<String, Object>of("items", List.of("x", "y", "z"));

        String result = templatingEngine.processTemplate(template, data);

        // _count is the 1-based index (count so far) in Qute
        Assertions.assertEquals("123", result);
    }

    @Test
    public void processTemplateWithIterationIndex() throws Exception {
        String template = "{#for item in items}{item_index}{/for}";
        var data = Map.<String, Object>of("items", List.of("x", "y", "z"));

        String result = templatingEngine.processTemplate(template, data);

        // _index is 0-based
        Assertions.assertEquals("012", result);
    }

    @Test
    public void processTemplateWithEmptyList() throws Exception {
        String template = "{#for item in items}{item}{/for}";
        var data = Map.<String, Object>of("items", List.of());

        String result = templatingEngine.processTemplate(template, data);

        Assertions.assertEquals("", result);
    }

    // --- Conditionals ---

    @Test
    public void processTemplateWithConditional() throws Exception {
        String template = "{#if show}visible{/if}";
        var data = new HashMap<String, Object>();
        data.put("show", true);

        String result = templatingEngine.processTemplate(template, data);

        Assertions.assertEquals("visible", result);
    }

    @Test
    public void processTemplateWithConditionalFalse() throws Exception {
        String template = "{#if show}visible{/if}";
        var data = new HashMap<String, Object>();
        data.put("show", false);

        String result = templatingEngine.processTemplate(template, data);

        Assertions.assertEquals("", result);
    }

    @Test
    public void processTemplateWithConditionalElse() throws Exception {
        String template = "{#if show}yes{#else}no{/if}";
        var data = new HashMap<String, Object>();
        data.put("show", false);

        String result = templatingEngine.processTemplate(template, data);

        Assertions.assertEquals("no", result);
    }

    // --- Missing variables ---

    @Test
    public void processTemplateWithMissingVariable() throws Exception {
        String template = "Hello {name}";
        var data = Map.<String, Object>of();

        // Should not throw (strict-rendering=false behavior)
        String result = templatingEngine.processTemplate(template, data);
        Assertions.assertNotNull(result);
    }

    // --- Multiple variables ---

    @Test
    public void processTemplateWithMultipleVariables() throws Exception {
        String template = "{greeting} {name}, welcome to {place}!";
        var data = Map.<String, Object>of("greeting", "Hello", "name", "Alice", "place", "EDDI");

        String result = templatingEngine.processTemplate(template, data);

        Assertions.assertEquals("Hello Alice, welcome to EDDI!", result);
    }

    // --- Complex: iteration + conditional + variables ---

    @Test
    public void processTemplateWithComplexStructure() throws Exception {
        String template = "[{#for item in items}{#if item_hasNext}\"{item}\",{#else}\"{item}\"{/if}{/for}]";
        var data = Map.<String, Object>of("items", List.of("a", "b"));

        String result = templatingEngine.processTemplate(template, data);

        Assertions.assertEquals("[\"a\",\"b\"]", result);
    }

    // --- TemplateMode is honoured (E9) ---

    @Test
    public void processTemplateInHtmlModeEscapesSubstitutedValues() throws Exception {
        String template = "<p>{value}</p>";
        var data = Map.<String, Object>of("value", "<script>alert('x') && \"y\"</script>");

        String result = templatingEngine.processTemplate(template, data, TemplateMode.HTML);

        // The literal markup of the template survives, the injected value does not
        Assertions.assertEquals("<p>&lt;script&gt;alert(&#39;x&#39;) &amp;&amp; &quot;y&quot;&lt;/script&gt;</p>", result);
    }

    @Test
    public void processTemplateInHtmlModeEscapesNestedValues() throws Exception {
        String template = "<b>{properties.name}</b> {#for tag in tags}<i>{tag}</i>{/for}";
        var data = Map.<String, Object>of("properties", Map.of("name", "<img src=x>"), "tags", List.of("<hr>"));

        String result = templatingEngine.processTemplate(template, data, TemplateMode.HTML);

        Assertions.assertEquals("<b>&lt;img src=x&gt;</b> <i>&lt;hr&gt;</i>", result);
    }

    @Test
    public void processTemplateInTextModeDoesNotEscape() throws Exception {
        String template = "{value}";
        var data = Map.<String, Object>of("value", "<b>bold & beautiful</b>");

        String result = templatingEngine.processTemplate(template, data, TemplateMode.TEXT);

        Assertions.assertEquals("<b>bold & beautiful</b>", result);
    }

    @Test
    public void processTemplateInJavaScriptModeEscapesSubstitutedValues() throws Exception {
        String template = "var greeting = '{value}';";
        var data = Map.<String, Object>of("value", "';alert(1);//");

        String result = templatingEngine.processTemplate(template, data, TemplateMode.JAVASCRIPT);

        Assertions.assertEquals("var greeting = '\\x27\\x3Balert\\x281\\x29\\x3B\\x2F\\x2F';", result);
    }

    // --- Compiled templates are cached (E8) ---

    @Test
    public void compiledTemplateIsParsedOnceAndReused() throws Exception {
        Engine realEngine = Engine.builder().addDefaults().strictRendering(false).build();
        Engine countingEngine = mock(Engine.class);
        when(countingEngine.parse(anyString())).thenAnswer(invocation -> realEngine.parse(invocation.getArgument(0, String.class)));
        var cachingTemplatingEngine = new TemplatingEngine(countingEngine);

        String template = "Hello {name}";
        for (int i = 0; i < 5; i++) {
            Assertions.assertEquals("Hello Alice", cachingTemplatingEngine.processTemplate(template, Map.of("name", "Alice")));
        }

        verify(countingEngine, times(1)).parse(template);
    }

    @Test
    public void distinctTemplatesAreParsedSeparately() throws Exception {
        Engine realEngine = Engine.builder().addDefaults().strictRendering(false).build();
        Engine countingEngine = mock(Engine.class);
        when(countingEngine.parse(anyString())).thenAnswer(invocation -> realEngine.parse(invocation.getArgument(0, String.class)));
        var cachingTemplatingEngine = new TemplatingEngine(countingEngine);

        Assertions.assertEquals("Hi Alice", cachingTemplatingEngine.processTemplate("Hi {name}", Map.of("name", "Alice")));
        Assertions.assertEquals("Bye Alice", cachingTemplatingEngine.processTemplate("Bye {name}", Map.of("name", "Alice")));

        verify(countingEngine, times(1)).parse("Hi {name}");
        verify(countingEngine, times(1)).parse("Bye {name}");
    }
}