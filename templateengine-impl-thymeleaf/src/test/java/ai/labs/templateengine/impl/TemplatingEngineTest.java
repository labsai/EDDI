package ai.labs.templateengine.impl;

import ai.labs.templateengine.ITemplatingEngine;
import ai.labs.templateengine.bootstrap.TemplateEngineModule;
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
        templatingEngine = new TemplatingEngine(engineModule.provideTemplateEngine());
    }

    @Test
    public void processTemplate() throws Exception {
        //setup
        String template = "Some kind of string having a [[${value}]]";
        HashMap<String, Object> dynamicAttributesMap = new HashMap<>();
        dynamicAttributesMap.put("value", "testValue");

        //test
        String result = templatingEngine.processTemplate(template, dynamicAttributesMap);

        //assert
        Assert.assertEquals("Some kind of string having a testValue", result);
    }
}