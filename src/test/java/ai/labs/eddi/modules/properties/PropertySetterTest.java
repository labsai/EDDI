/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.ExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionProvider;
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
        // setup
        String testStringExpressions = "property(someMeaning(someValue)),noProperty(someMeaning(someValue))";
        when(expressionProvider.parseExpressions(eq(testStringExpressions)))
                .thenAnswer(invocation -> new Expressions(new Expression("property", new Expression("someMeaning", new Value("someValue"))),
                        new Expression("noProperty", new Expression("someMeaning", new Value("someValue")))));
        PropertySetter propertySetter = new PropertySetter(new LinkedList<>());
        Property expectedPropertyEntry = new Property("someMeaning", "someValue", conversation);

        // test
        List<Property> propertyEntries = propertySetter.extractProperties(expressionProvider.parseExpressions(testStringExpressions));

        // assert
        verify(expressionProvider, times(1)).parseExpressions(testStringExpressions);
        Assertions.assertEquals(Collections.singletonList(expectedPropertyEntry), propertyEntries);
    }

    @Test
    public void extractMoreComplexProperties() {
        // setup
        String testStringExpressions = "property(someMeaning(someSubMeaning(someValue)))," + "property(someMeaning(someValue, someOtherValue))";
        when(expressionProvider.parseExpressions(eq(testStringExpressions))).thenAnswer(invocation -> new Expressions(
                new Expression("property", new Expression("someMeaning", new Expression("someSubMeaning", new Value("someValue")))),
                new Expression("property", new Expression("someMeaning", new Value("someValue"), new Value("someOtherValue")))));
        PropertySetter propertySetter = new PropertySetter(new LinkedList<>());
        List<Property> expectedPropertyEntries = Arrays.asList(
                new Property(String.join(".", "someMeaning", "someSubMeaning"), "someValue", conversation),
                new Property("someMeaning", "someValue", conversation));

        // test
        List<Property> propertyEntries = propertySetter.extractProperties(expressionProvider.parseExpressions(testStringExpressions));

        // assert
        verify(expressionProvider, times(1)).parseExpressions(testStringExpressions);
        Assertions.assertEquals(expectedPropertyEntries, propertyEntries);
    }

    private static Property single(String leaf) {
        Expressions expressions = new Expressions(new Expression("property", new Expression("x", new Expression(leaf))));
        List<Property> result = new PropertySetter(new LinkedList<>()).extractProperties(expressions);
        Assertions.assertEquals(1, result.size());
        return result.get(0);
    }

    @Test
    public void leafValuesAreKeptVerbatim() {
        Assertions.assertEquals("api.openai.com", single("api.openai.com").getValueString());
        Assertions.assertEquals("1.2.3", single("1.2.3").getValueString());
        Assertions.assertEquals("01234", single("01234").getValueString());
        Assertions.assertEquals("3.14", single("3.14").getValueString());
        Assertions.assertEquals("-5", single("-5").getValueString());
        Assertions.assertEquals("１", single("１").getValueString());
    }

    @Test
    public void plainIntegersBecomeNumbers() {
        Assertions.assertEquals(42, single("42").getValueInt());
        Assertions.assertEquals(0, single("0").getValueInt());
        Assertions.assertEquals(4915112345678L, single("4915112345678").getValueLong());
    }

    @Test
    public void tooLongIntegerStaysAString() {
        String huge = "9".repeat(30);
        Assertions.assertEquals(huge, single(huge).getValueString());
    }

    @Test
    public void nineteenDigitLeafIsALongOnlyWhenItFits() {
        Assertions.assertEquals(Long.MAX_VALUE, single("9223372036854775807").getValueLong());
        Assertions.assertEquals(1000000000000000000L, single("1000000000000000000").getValueLong());
        Assertions.assertEquals("9223372036854775808", single("9223372036854775808").getValueString());
    }

    @Test
    public void digitsOfAnotherScriptAreKeptAsWritten() {
        Assertions.assertEquals("١٢", single("١٢").getValueString());

        // the exact expression, parsed by the real provider
        var provider = new ExpressionProvider(new ExpressionFactory());
        List<Property> parsed = new PropertySetter(new LinkedList<>()).extractProperties(
                provider.parseExpressions("property(customerCode(١٢))"));
        Assertions.assertEquals(1, parsed.size());
        Assertions.assertEquals("customerCode", parsed.get(0).getName());
        Assertions.assertEquals("١٢", parsed.get(0).getValueString());
    }

    @Test
    public void booleansAreRecognised() {
        Assertions.assertEquals(Boolean.TRUE, single("TRUE").getValueBoolean());
    }
}
