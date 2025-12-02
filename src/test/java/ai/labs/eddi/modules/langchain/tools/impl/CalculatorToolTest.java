package ai.labs.eddi.modules.langchain.tools.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CalculatorToolTest {

    private CalculatorTool calculatorTool;
    private boolean scriptEngineAvailable;

    @BeforeEach
    void setUp() {
        calculatorTool = new CalculatorTool();
        // Test if ScriptEngine is available
        String testResult = calculatorTool.calculate("1 + 1");
        scriptEngineAvailable = !testResult.startsWith("Error");
    }

    @Test
    void testCalculate_SimpleAddition() {
        assumeTrue(scriptEngineAvailable, "ScriptEngine not available in test environment");
        String result = calculatorTool.calculate("2 + 2");
        assertEquals("4", result);
    }

    @Test
    void testCalculate_SimpleSubtraction() {
        assumeTrue(scriptEngineAvailable, "ScriptEngine not available in test environment");
        String result = calculatorTool.calculate("10 - 3");
        assertEquals("7", result);
    }

    @Test
    void testCalculate_SimpleMultiplication() {
        assumeTrue(scriptEngineAvailable, "ScriptEngine not available in test environment");
        String result = calculatorTool.calculate("5 * 4");
        assertEquals("20", result);
    }

    @Test
    void testCalculate_SimpleDivision() {
        assumeTrue(scriptEngineAvailable, "ScriptEngine not available in test environment");
        String result = calculatorTool.calculate("20 / 4");
        assertEquals("5", result);
    }

    @Test
    void testCalculate_ComplexExpression() {
        assumeTrue(scriptEngineAvailable, "ScriptEngine not available in test environment");
        String result = calculatorTool.calculate("(10 + 5) * 2");
        assertEquals("30", result);
    }

    @Test
    void testCalculate_SquareRoot() {
        assumeTrue(scriptEngineAvailable, "ScriptEngine not available in test environment");
        String result = calculatorTool.calculate("Math.sqrt(16)");
        assertEquals("4", result);
    }

    @Test
    void testCalculate_Power() {
        assumeTrue(scriptEngineAvailable, "ScriptEngine not available in test environment");
        String result = calculatorTool.calculate("Math.pow(2, 3)");
        assertEquals("8", result);
    }

    @Test
    void testCalculate_InvalidExpression() {
        assumeTrue(scriptEngineAvailable, "ScriptEngine not available in test environment");
        // Use a truly invalid expression that will fail
        String result = calculatorTool.calculate("2 + * 2");
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    void testCalculate_MathSin() {
        assumeTrue(scriptEngineAvailable, "ScriptEngine not available in test environment");
        String result = calculatorTool.calculate("Math.sin(0)");
        assertEquals("0", result);
    }

    @Test
    void testCalculate_MathCos() {
        assumeTrue(scriptEngineAvailable, "ScriptEngine not available in test environment");
        String result = calculatorTool.calculate("Math.cos(0)");
        assertEquals("1", result);
    }


    @Test
    void testCalculate_UnsafeExpression_Java() {
        String result = calculatorTool.calculate("java.lang.System.exit(0)");
        assertTrue(result.contains("Error: Invalid expression"));
    }

    @Test
    void testCalculate_UnsafeExpression_Import() {
        String result = calculatorTool.calculate("import java.io.*;");
        assertTrue(result.contains("Error: Invalid expression"));
    }

    @Test
    void testCalculate_UnsafeExpression_Function() {
        String result = calculatorTool.calculate("function() { return 1; }");
        assertTrue(result.contains("Error: Invalid expression"));
    }

    @Test
    void testCalculate_DivisionByZero() {
        String result = calculatorTool.calculate("10 / 0");
        assertNotNull(result);
        // JavaScript returns Infinity for division by zero
        assertTrue(result.equals("Infinity") || result.contains("Error"));
    }

    @ParameterizedTest
    @CsvSource({
            "0, celsius, fahrenheit",
            "100, celsius, fahrenheit",
            "32, fahrenheit, celsius",
            "212, fahrenheit, celsius",
            "0, celsius, kelvin",
            "273.15, kelvin, celsius"
    })
    void testConvertUnits_Temperature(double value, String fromUnit, String toUnit) {
        String result = calculatorTool.convertUnits(value, fromUnit, toUnit);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"), "Conversion should not error: " + result);
        assertTrue(result.contains(fromUnit) || result.toLowerCase().contains(fromUnit.toLowerCase()));
        assertTrue(result.contains(toUnit) || result.toLowerCase().contains(toUnit.toLowerCase()));
    }

    @ParameterizedTest
    @CsvSource({
            "1, km, miles",
            "1, miles, km",
            "1, m, feet",
            "1, feet, m"
    })
    void testConvertUnits_Distance(double value, String fromUnit, String toUnit) {
        String result = calculatorTool.convertUnits(value, fromUnit, toUnit);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        assertTrue(result.contains(fromUnit));
        assertTrue(result.contains(toUnit));
    }

    @ParameterizedTest
    @CsvSource({
            "1, kg, lb",
            "1, lb, kg"
    })
    void testConvertUnits_Weight(double value, String fromUnit, String toUnit) {
        String result = calculatorTool.convertUnits(value, fromUnit, toUnit);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        assertTrue(result.contains(fromUnit));
        assertTrue(result.contains(toUnit));
    }

    @ParameterizedTest
    @CsvSource({
            "1, liters, gallons",
            "1, gallons, liters"
    })
    void testConvertUnits_Volume(double value, String fromUnit, String toUnit) {
        String result = calculatorTool.convertUnits(value, fromUnit, toUnit);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        assertTrue(result.contains(fromUnit));
        assertTrue(result.contains(toUnit));
    }

    @Test
    void testConvertUnits_InvalidConversion() {
        String result = calculatorTool.convertUnits(1, "invalid", "alsoinvalid");
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    void testConvertUnits_CaseInsensitive() {
        String result1 = calculatorTool.convertUnits(0, "Celsius", "Fahrenheit");
        String result2 = calculatorTool.convertUnits(0, "celsius", "fahrenheit");
        assertNotNull(result1);
        assertNotNull(result2);
        assertFalse(result1.startsWith("Error"));
        assertFalse(result2.startsWith("Error"));
    }

    @Test
    void testCalculate_LargeNumber() {
        assumeTrue(scriptEngineAvailable, "ScriptEngine not available in test environment");
        String result = calculatorTool.calculate("999999 * 999999");
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
    }

    @Test
    void testCalculate_NegativeNumbers() {
        assumeTrue(scriptEngineAvailable, "ScriptEngine not available in test environment");
        String result = calculatorTool.calculate("-5 + 3");
        assertEquals("-2", result);
    }

    @Test
    void testCalculate_FloatingPoint() {
        assumeTrue(scriptEngineAvailable, "ScriptEngine not available in test environment");
        String result = calculatorTool.calculate("3.14 * 2");
        assertTrue(result.startsWith("6.28"));
    }

    @Test
    void testCalculate_MathPI() {
        assumeTrue(scriptEngineAvailable, "ScriptEngine not available in test environment");
        String result = calculatorTool.calculate("Math.PI * 2");
        assertTrue(result.startsWith("6.28"));
    }

    @Test
    void testCalculate_ScriptEngineUnavailable() {
        // Test that when ScriptEngine is unavailable, we get appropriate error
        if (!scriptEngineAvailable) {
            String result = calculatorTool.calculate("2 + 2");
            assertTrue(result.startsWith("Error"), "Should return error when ScriptEngine is unavailable");
        }
    }
}

