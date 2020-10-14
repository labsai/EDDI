package ai.labs.templateengine.impl;

import ai.labs.templateengine.ITemplatingEngine;
import ai.labs.templateengine.bootstrap.TemplateEngineModule;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;

/**
 * @author ginccc
 */
public class TemplatingEngineTest {
    private ITemplatingEngine templatingEngine;

    @Before
    public void setup() {
        TemplateEngineModule engineModule = new TemplateEngineModule();
        templatingEngine = new TemplatingEngine(
                engineModule.provideTextTemplateEngine(null),
                engineModule.provideHtmlTemplateEngine(null),
                engineModule.provideJavaScriptTemplateEngine(null));
    }

    @Test
    public void processTemplateWithStringContext() throws Exception {
        //setup
        String template = "Some kind of string having a [[${value}]]";
        HashMap<String, Object> dynamicAttributesMap = new HashMap<>();
        dynamicAttributesMap.put("value", "testValue");

        //test
        String result = templatingEngine.processTemplate(template, dynamicAttributesMap);

        //assert
        Assert.assertEquals("Some kind of string having a testValue", result);
    }

    @Test
    public void processTemplateWithObjectContext() throws Exception {
        //setup
        String template = "Some kind of string having a [[${obj.value}]]";
        HashMap<String, Object> dynamicAttributesMap = new HashMap<>();
        dynamicAttributesMap.put("obj", new TestObject("testValue"));

        //test
        String result = templatingEngine.processTemplate(template, dynamicAttributesMap);

        //assert
        Assert.assertEquals("Some kind of string having a testValue", result);
    }

    @Getter
    @Setter
    @AllArgsConstructor
    private static class TestObject {
        private String value;
    }
}