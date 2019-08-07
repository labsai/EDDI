package ai.labs.property.impl;

import ai.labs.expressions.Expression;
import ai.labs.expressions.Expressions;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.expressions.value.Value;
import ai.labs.models.Property;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static ai.labs.models.Property.Scope.conversation;
import static org.mockito.Mockito.*;

/**
 * @author ginccc
 */
public class PropertySetterTest {
    private IExpressionProvider expressionProvider;

    @Before
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
        PropertySetter propertySetter = new PropertySetter();
        Property expectedPropertyEntry = new Property("someMeaning", "someValue", conversation);

        //test
        List<Property> propertyEntries = propertySetter.extractProperties(expressionProvider.parseExpressions(testStringExpressions));

        //assert
        verify(expressionProvider, times(1)).parseExpressions(testStringExpressions);
        Assert.assertEquals(Collections.singletonList(expectedPropertyEntry), propertyEntries);
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
        PropertySetter propertySetter = new PropertySetter();
        List<Property> expectedPropertyEntries = Arrays.asList(
                new Property(String.join(".", "someMeaning", "someSubMeaning"), "someValue", conversation),
                new Property("someMeaning", "someValue", conversation));

        //test
        List<Property> propertyEntries = propertySetter.extractProperties(expressionProvider.parseExpressions(testStringExpressions));

        //assert
        verify(expressionProvider, times(1)).parseExpressions(testStringExpressions);
        Assert.assertEquals(expectedPropertyEntries, propertyEntries);
    }
}