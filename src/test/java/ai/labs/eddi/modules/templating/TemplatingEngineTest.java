package ai.labs.eddi.modules.templating;

import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.modules.templating.bootstrap.TemplateEngineModule;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

/**
 * @author ginccc
 */

public class TemplatingEngineTest {
    private static ITemplatingEngine templatingEngine;

    @BeforeEach
    public void setUp() {
        TemplateEngineModule engineModule = new TemplateEngineModule(null, null);
        var objectMapper = new ObjectMapper();
        new SerializationCustomizer(true).customize(objectMapper);
        templatingEngine = new TemplatingEngine(
                engineModule.provideTextTemplateEngine(),
                engineModule.provideHtmlTemplateEngine(),
                engineModule.provideJavaScriptTemplateEngine());
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
        Assertions.assertEquals("Some kind of string having a testValue", result);
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
        Assertions.assertEquals("Some kind of string having a testValue", result);
    }

    @Getter
    @Setter
    @AllArgsConstructor
    private static class TestObject {
        private String value;
    }
}