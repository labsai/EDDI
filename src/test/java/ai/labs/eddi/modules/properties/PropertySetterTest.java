package ai.labs.eddi.modules.properties;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.expressions.value.Value;
import ai.labs.eddi.modules.properties.impl.PropertySetter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static ai.labs.eddi.configs.properties.model.Property.Scope.conversation;
import static org.mockito.Mockito.*;

/**
 * @author ginccc
 */
public class PropertySetterTest {
    private static IExpressionProvider expressionProvider;

    @BeforeEach
    public void setUp() {
        expressionProvider = mock(IExpressionProvider.class);
    }

    @Test
    public void extractProperties() {
        //setup
        String testStringExpressions = "property(someMeaning(someValue)),noProperty(someMeaning(someValue))";
        when(expressionProvider.parseExpressions(eq(testStringExpressions))).thenAnswer(invocation ->
                new Expressions(new Expression("property",
                        new Expression("someMeaning",
                                new Value("someValue"))),
                        new Expression("noProperty",
                                new Expression("someMeaning",
                                        new Value("someValue")))
                ));
        PropertySetter propertySetter = new PropertySetter(new LinkedList<>());
        Property expectedPropertyEntry = new Property("someMeaning", "someValue", conversation);

        //test
        List<Property> propertyEntries = propertySetter.extractProperties(expressionProvider.parseExpressions(testStringExpressions));

        //assert
        verify(expressionProvider, times(1)).parseExpressions(testStringExpressions);
        Assertions.assertEquals(Collections.singletonList(expectedPropertyEntry), propertyEntries);
    }

    @Test
    public void extractMoreComplexProperties() {
        //setup
        String testStringExpressions = "property(someMeaning(someSubMeaning(someValue)))," +
                "property(someMeaning(someValue, someOtherValue))";
        when(expressionProvider.parseExpressions(eq(testStringExpressions))).thenAnswer(invocation ->
                new Expressions(new Expression("property",
                        new Expression("someMeaning",
                                new Expression("someSubMeaning",
                                        new Value("someValue")))),
                        new Expression("property",
                                new Expression("someMeaning",
                                        new Value("someValue"), new Value("someOtherValue")))
                ));
        PropertySetter propertySetter = new PropertySetter(new LinkedList<>());
        List<Property> expectedPropertyEntries = Arrays.asList(
                new Property(String.join(".", "someMeaning", "someSubMeaning"), "someValue", conversation),
                new Property("someMeaning", "someValue", conversation));

        //test
        List<Property> propertyEntries = propertySetter.extractProperties(expressionProvider.parseExpressions(testStringExpressions));

        //assert
        verify(expressionProvider, times(1)).parseExpressions(testStringExpressions);
        Assertions.assertEquals(expectedPropertyEntries, propertyEntries);
    }
}