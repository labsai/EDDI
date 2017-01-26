package ai.labs.core.behavior;

import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.resources.rest.behavior.model.BehaviorConfiguration;
import ai.labs.resources.rest.behavior.model.BehaviorGroupConfiguration;
import ai.labs.resources.rest.behavior.model.BehaviorRuleConfiguration;
import ai.labs.resources.rest.behavior.model.BehaviorRuleElementConfiguration;
import ai.labs.runtime.DependencyInjector;
import org.junit.Before;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class SerializeJsonBehaviorRulesTest {

    private static class DialogBuilder1 {

        private String dialogName;
        private List<BehaviorRuleBuilder> behaviorRuleBuilders = new ArrayList<BehaviorRuleBuilder>();

        private DialogBuilder1(String dialogName) {
            this.dialogName = dialogName;
        }

        public BehaviorRuleBuilder rule(String name) {
            BehaviorRuleBuilder builder = new BehaviorRuleBuilder(name);
            this.behaviorRuleBuilders.add(builder);
            return builder;
        }

        public BehaviorConfiguration build() {
            BehaviorConfiguration result = new BehaviorConfiguration();
            BehaviorGroupConfiguration groupConfiguration = new BehaviorGroupConfiguration();
            result.getBehaviorGroups().add(groupConfiguration);

            for (BehaviorRuleBuilder builder : behaviorRuleBuilders) {
                groupConfiguration.getBehaviorRules().add(builder.build());
            }

            return result;
        }

        public abstract class SetBuilder {

            protected List<ElementBuilder> elements = new ArrayList<ElementBuilder>();

            ElementBuilder input(String expression, String occurrence) {
                ElementBuilder b = new ElementBuilder(this);
                elements.add(b.type("input").value("expression", expression).value("occurrence", occurrence));
                return b;
            }

            public DialogBuilder1 dialog() {
                return DialogBuilder1.this;
            }

/*
            public ElementBuilder child() {
                ElementBuilder elementBuilder = new ElementBuilder(this);
                elements.add(elementBuilder);
                return elementBuilder;
            }
*/
        }

        public class ElementBuilder extends SetBuilder {

            String type;

            private Map<String, String> values = new HashMap<String, String>();
            private SetBuilder parent;

            public ElementBuilder(SetBuilder parent) {
                this.parent = parent;
            }

            public ElementBuilder type(String type) {
                this.type = type;
                return this;
            }

            public ElementBuilder value(String name, String value) {
                values.put(name, value);
                return this;
            }

            public BehaviorRuleElementConfiguration build() {
                BehaviorRuleElementConfiguration result = new BehaviorRuleElementConfiguration();
                result.setType(type);
                result.getValues().putAll(values);

                for (ElementBuilder element : elements) {
                    result.getChildren().add(element.build());
                }

                return result;
            }

            public SetBuilder parent() {
                return parent;


            }
        }

        public class BehaviorRuleBuilder extends SetBuilder {

            private String name;

            public BehaviorRuleBuilder(String name) {
                this.name = name;
            }

            private BehaviorRuleConfiguration build() {
                BehaviorRuleConfiguration behaviorRule = new BehaviorRuleConfiguration();
                behaviorRule.setName(name);

                for (ElementBuilder element : elements) {
                    behaviorRule.getChildren().add(element.build());
                }

                return behaviorRule;
            }

        }
    }

    private IExpressionProvider expessionUtilities;

    @Before
    public void setup() {
        expessionUtilities = DependencyInjector.getInstance().getInstance(IExpressionProvider.class);
    }

    /*@Test
    public void testInputMatcher() throws IOException {
        // setup
        Expression testExpression = expessionUtilities.createExpression("predicate", "value1", "value2");
        IExtension inputMatcher = new InputMatcher(Collections.singletonList(testExpression));
        BehaviorRule behaviorRule = new BehaviorRule("rule1");
        behaviorRule.getExtensions().add((inputMatcher));
        BehaviorSet behaviorSet = new BehaviorSet();
        BehaviorGroup behaviorGroup = new BehaviorGroup();
        behaviorSet.getBehaviorGroups().add(behaviorGroup);
        behaviorGroup.getBehaviorRules().add(behaviorRule);

        // test
        String result = BehaviorSerialization.serialize(behaviorSet);

        // assert
        Assert.assertEquals("{\"behaviorGroups\":[{\"name\":null,\"behaviorRules\":[{\"name\":\"rule1\",\"actions\":[],\"children\":[{\"type\":\"inputmatcher\",\"values\":{\"expressions\":\"predicate(value1, value2)\"},\"children\":[]}]}]}]}", result);
    }

    @Test
    public void testPropertyMatcher() throws IOException {
        //setup
        PropertyMatcher propertyMatcher = new PropertyMatcher(Arrays.asList(new Expression("test")));

        BehaviorRule behaviorRule = new BehaviorRule("rule1");
        behaviorRule.getExtensions().add((propertyMatcher));
        BehaviorSet behaviorSet = new BehaviorSet();
        BehaviorGroup behaviorGroup = new BehaviorGroup();
        behaviorSet.getBehaviorGroups().add(behaviorGroup);
        behaviorGroup.getBehaviorRules().add(behaviorRule);

        //test
        String result = BehaviorSerialization.serialize(behaviorSet);

        //assert
        Assert.assertEquals("{\"behaviorGroups\":[{\"name\":null,\"behaviorRules\":[{\"name\":\"rule1\",\"actions\":[],\"children\":[{\"type\":\"propertymatcher\",\"values\":{\"expressions\":\"test\"},\"children\":[]}]}]}]}", result);
    }

    @Test
    public void testDependency() throws IOException {
        // setup
        IExtension dependency = new Dependency("referenceName");
        BehaviorRule behaviorRule = new BehaviorRule("rule1");
        behaviorRule.getExtensions().add((dependency));
        BehaviorSet behaviorSet = new BehaviorSet();
        BehaviorGroup behaviorGroup = new BehaviorGroup();
        behaviorSet.getBehaviorGroups().add(behaviorGroup);
        behaviorGroup.getBehaviorRules().add(behaviorRule);

        // test
        String result = BehaviorSerialization.serialize(behaviorSet);

        // assert
        Assert.assertEquals("{\"behaviorGroups\":[{\"name\":null,\"behaviorRules\":[{\"name\":\"rule1\",\"actions\":[],\"children\":[{\"type\":\"dependency\",\"values\":{\"reference\":\"referenceName\"},\"children\":[]}]}]}]}", result);
    }

    @Test
    public void testOccurrence() throws IOException {
        //setup
        Occurrence occurrence = new Occurrence();
        occurrence.setBehaviorRuleName("test1");

        BehaviorRule behaviorRule = new BehaviorRule("rule1");
        behaviorRule.getExtensions().add((occurrence));
        BehaviorSet behaviorSet = new BehaviorSet();
        BehaviorGroup behaviorGroup = new BehaviorGroup();
        behaviorSet.getBehaviorGroups().add(behaviorGroup);
        behaviorGroup.getBehaviorRules().add(behaviorRule);

        //test
        String result = BehaviorSerialization.serialize(behaviorSet);

        //assert
        Assert.assertEquals("{\"behaviorGroups\":[{\"name\":null,\"behaviorRules\":[{\"name\":\"rule1\",\"actions\":[],\"children\":[{\"type\":\"occurrence\",\"values\":{\"behaviorRuleName\":\"test1\",\"maxOccurrence\":\"1\"},\"children\":[]}]}]}]}", result);
    }

    @Test
    public void testConnector() throws IOException {
        //setup
        Connector connector = new Connector(Connector.Operator.AND);
        connector.addExecutable(new Occurrence());
        connector.addExecutable(new Occurrence());

        BehaviorRule behaviorRule = new BehaviorRule("rule1");
        behaviorRule.getExtensions().add(connector);
        BehaviorSet behaviorSet = new BehaviorSet();
        BehaviorGroup behaviorGroup = new BehaviorGroup();
        behaviorSet.getBehaviorGroups().add(behaviorGroup);
        behaviorGroup.getBehaviorRules().add(behaviorRule);

        //test
        String result = BehaviorSerialization.serialize(behaviorSet);

        //assert
        Assert.assertEquals("{\"behaviorGroups\":[{\"name\":null,\"behaviorRules\":[{\"name\":\"rule1\",\"actions\":[],\"children\":[{\"type\":\"connector\",\"values\":{\"operator\":\"AND\"},\"children\":[{\"type\":\"occurrence\",\"values\":{\"behaviorRuleName\":null,\"maxOccurrence\":\"1\"},\"children\":[]},{\"type\":\"occurrence\",\"values\":{\"behaviorRuleName\":null,\"maxOccurrence\":\"1\"},\"children\":[]}]}]}]}]}", result);
    }

    @Test
    public void testNegation() throws IOException {
        //setup
        Negation negation = new Negation();
        Occurrence occurrence = new Occurrence();
        occurrence.setBehaviorRuleName("rule2");
        negation.setExtension(occurrence);

        BehaviorRule behaviorRule = new BehaviorRule("rule1");
        behaviorRule.getExtensions().add((negation));
        BehaviorSet behaviorSet = new BehaviorSet();
        BehaviorGroup behaviorGroup = new BehaviorGroup();
        behaviorSet.getBehaviorGroups().add(behaviorGroup);
        behaviorGroup.getBehaviorRules().add(behaviorRule);

        //test
        String result = BehaviorSerialization.serialize(behaviorSet);

        //assert
        Assert.assertEquals("{\"behaviorGroups\":[{\"name\":null,\"behaviorRules\":[{\"name\":\"rule1\",\"actions\":[],\"children\":[{\"type\":\"negation\",\"values\":{},\"children\":[{\"type\":\"occurrence\",\"values\":{\"behaviorRuleName\":\"rule2\",\"maxOccurrence\":\"1\"},\"children\":[]}]}]}]}]}", result);
    }

    @Test
    public void testResultSize() throws IOException {
        //setup
        ResultSize resultSize = new ResultSize();
        resultSize.setEqual(1);
        resultSize.setMax(2);
        resultSize.setMin(3);

        BehaviorRule behaviorRule = new BehaviorRule("rule1");
        behaviorRule.getExtensions().add((resultSize));
        BehaviorSet behaviorSet = new BehaviorSet();
        BehaviorGroup behaviorGroup = new BehaviorGroup();
        behaviorSet.getBehaviorGroups().add(behaviorGroup);
        behaviorGroup.getBehaviorRules().add(behaviorRule);

        //test
        String result = BehaviorSerialization.serialize(behaviorSet);

        //assert
        Assert.assertEquals("{\"behaviorGroups\":[{\"name\":null,\"behaviorRules\":[{\"name\":\"rule1\",\"actions\":[],\"children\":[{\"type\":\"resultSize\",\"values\":{\"equal\":\"1\",\"min\":\"3\",\"max\":\"2\"},\"children\":[]}]}]}]}", result);
    }

    @Test
    public void testOutputReference() throws IOException {
        //setup
        OutputReference outputReference = new OutputReference(Arrays.asList(new Expression("test")), "inputValue", "sessionValue");

        BehaviorRule behaviorRule = new BehaviorRule("rule1");
        behaviorRule.getExtensions().add((outputReference));
        BehaviorSet behaviorSet = new BehaviorSet();
        BehaviorGroup behaviorGroup = new BehaviorGroup();
        behaviorSet.getBehaviorGroups().add(behaviorGroup);
        behaviorGroup.getBehaviorRules().add(behaviorRule);

        //test
        String result = BehaviorSerialization.serialize(behaviorSet);

        //assert
        Assert.assertEquals("{\"behaviorGroups\":[{\"name\":null,\"behaviorRules\":[{\"name\":\"rule1\",\"actions\":[],\"children\":[{\"type\":\"outputReference\",\"values\":{\"filter\":\"test\",\"inputValue\":\"inputValue\",\"sessionValue\":\"sessionValue\"},\"children\":[]}]}]}]}", result);
    }*/
}
