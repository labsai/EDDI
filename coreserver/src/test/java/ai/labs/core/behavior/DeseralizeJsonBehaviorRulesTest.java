package ai.labs.core.behavior;

import ai.labs.core.behavior.extensions.descriptor.BehaviorRuleExtensionRegistry;
import ai.labs.core.behavior.extensions.descriptor.ExtensionDescriptorBuilder;
import ai.labs.core.behavior.extensions.descriptor.IExtensionDescriptor;

/**
 * @author ginccc
 */
public class DeseralizeJsonBehaviorRulesTest {
/*    @Test
    public void testInputMatcher() throws IOException, DeserializationException {
        // setup
        final String jsonString = "{\"behaviorGroups\":[{\"name\":\"\",\"behaviorRules\":[{\"name\":\"\",\"children\":[{\"type\":\"inputmatcher\",\"values\":{\"expressions\":\"predicate(value1, value2)\"},\"children\":[]}]}]}]}";

        // test
        createInputMatcherDummy();
        BehaviorSet result = BehaviorSerialization.deserialize(jsonString);

        // assert
        BehaviorRule resultBehaviorRule = result.getBehaviorGroups().get(0).getBehaviorRules().get(0);
        String expressions = resultBehaviorRule.getExtensions().get(0).getValues().get("expressions");
        Assert.assertEquals("inputmatcher", resultBehaviorRule.getExtensions().get(0).getId());
        Assert.assertEquals("predicate(value1, value2)", expressions);
    }

    private void createInputMatcherDummy() {
        IExtensionDescriptor descriptor = ExtensionDescriptorBuilder.create("inputmatcher", "", "ai.labs.core.behavior.extensions.InputMatcher").attribute("expressions", "List<Expression>", "").build();
        BehaviorRuleExtensionRegistry.getInstance().register(descriptor.getId(), descriptor);
    }

    @Test
    public void testOccurence() throws IOException, DeserializationException {
        // setup
        final String jsonString = "{\"behaviorGroups\":[{\"name\":\"\",\"behaviorRules\":[{\"name\":\"\",\"children\":[{\"type\":\"occurrence\",\"values\":{\"maxOccurrence\":\"1\",\"behaviorRuleName\":\"test1\" },\"children\":[]}]}]}]}";

        // test
        createOccurenceDummy();
        BehaviorSet result = BehaviorSerialization.deserialize(jsonString);

        // assert
        BehaviorRule resultBehaviorRule = result.getBehaviorGroups().get(0).getBehaviorRules().get(0);
        Map<String, String> values = resultBehaviorRule.getExtensions().get(0).getValues();
        String maxOccurrence = values.get("maxOccurrence");
        String behaviorRuleName = values.get("behaviorRuleName");
        Assert.assertEquals("occurrence", resultBehaviorRule.getExtensions().get(0).getId());
        Assert.assertEquals("1", maxOccurrence);
        Assert.assertEquals("test1", behaviorRuleName);
    }*/

    private void createOccurenceDummy() {
        IExtensionDescriptor descriptor = ExtensionDescriptorBuilder.create("occurrence", "", "ai.labs.core.behavior.extensions.Occurrence").build();
        BehaviorRuleExtensionRegistry.getInstance().register(descriptor.getId(), descriptor);
    }
}
