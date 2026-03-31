package ai.labs.eddi.modules.llm.tools.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CalculatorToolTest {

    private CalculatorTool calculatorTool;

    @BeforeEach
    void setUp() {
        calculatorTool = new CalculatorTool();
    }

    // === Basic Arithmetic ===

    @Test
    void testCalculate_SimpleAddition() {
        assertEquals("4", calculatorTool.calculate("2 + 2"));
    }

    @Test
    void testCalculate_SimpleSubtraction() {
        assertEquals("7", calculatorTool.calculate("10 - 3"));
    }

    @Test
    void testCalculate_SimpleMultiplication() {
        assertEquals("20", calculatorTool.calculate("5 * 4"));
    }

    @Test
    void testCalculate_SimpleDivision() {
        assertEquals("5", calculatorTool.calculate("20 / 4"));
    }

    @Test
    void testCalculate_Modulo() {
        assertEquals("1", calculatorTool.calculate("10 % 3"));
    }

    @Test
    void testCalculate_Power() {
        assertEquals("8", calculatorTool.calculate("2 ^ 3"));
    }

    @Test
    void testCalculate_ComplexExpression() {
        assertEquals("30", calculatorTool.calculate("(10 + 5) * 2"));
    }

    @Test
    void testCalculate_NestedParentheses() {
        assertEquals("20", calculatorTool.calculate("((2 + 3) * (1 + 1)) * 2"));
    }

    @Test
    void testCalculate_NegativeNumbers() {
        assertEquals("-2", calculatorTool.calculate("-5 + 3"));
    }

    @Test
    void testCalculate_LeadingPositiveSign() {
        assertEquals("5", calculatorTool.calculate("+5"));
    }

    @Test
    void testCalculate_DoubleNegative() {
        assertEquals("5", calculatorTool.calculate("--5"));
    }

    @Test
    void testCalculate_FloatingPoint() {
        String result = calculatorTool.calculate("3.14 * 2");
        assertTrue(result.startsWith("6.28"));
    }

    @Test
    void testCalculate_LargeNumber() {
        String result = calculatorTool.calculate("999999 * 999999");
        assertEquals("999998000001", result);
    }

    @Test
    void testCalculate_DivisionByZero() {
        assertEquals("Infinity", calculatorTool.calculate("10 / 0"));
    }

    @Test
    void testCalculate_NegativeDivisionByZero() {
        assertEquals("-Infinity", calculatorTool.calculate("-10 / 0"));
    }

    // === Math Functions ===

    @Test
    void testCalculate_Sqrt() {
        assertEquals("4", calculatorTool.calculate("sqrt(16)"));
    }

    @Test
    void testCalculate_SqrtWithMathPrefix() {
        assertEquals("4", calculatorTool.calculate("Math.sqrt(16)"));
    }

    @Test
    void testCalculate_Pow() {
        assertEquals("8", calculatorTool.calculate("pow(2, 3)"));
    }

    @Test
    void testCalculate_PowWithMathPrefix() {
        assertEquals("8", calculatorTool.calculate("Math.pow(2, 3)"));
    }

    @Test
    void testCalculate_Abs() {
        assertEquals("5", calculatorTool.calculate("abs(-5)"));
    }

    @Test
    void testCalculate_Ceil() {
        assertEquals("4", calculatorTool.calculate("ceil(3.2)"));
    }

    @Test
    void testCalculate_Floor() {
        assertEquals("3", calculatorTool.calculate("floor(3.8)"));
    }

    @Test
    void testCalculate_Round() {
        assertEquals("4", calculatorTool.calculate("round(3.5)"));
    }

    @Test
    void testCalculate_Min() {
        assertEquals("2", calculatorTool.calculate("min(2, 5)"));
    }

    @Test
    void testCalculate_Max() {
        assertEquals("5", calculatorTool.calculate("max(2, 5)"));
    }

    @Test
    void testCalculate_Sin() {
        assertEquals("0", calculatorTool.calculate("sin(0)"));
    }

    @Test
    void testCalculate_Cos() {
        assertEquals("1", calculatorTool.calculate("cos(0)"));
    }

    @Test
    void testCalculate_Log() {
        assertEquals("0", calculatorTool.calculate("log(1)"));
    }

    @Test
    void testCalculate_Exp() {
        assertEquals("1", calculatorTool.calculate("exp(0)"));
    }

    @Test
    void testCalculate_Log10() {
        assertEquals("2", calculatorTool.calculate("log10(100)"));
    }

    @Test
    void testCalculate_Cbrt() {
        assertEquals("3", calculatorTool.calculate("cbrt(27)"));
    }

    // === Constants ===

    @Test
    void testCalculate_PI() {
        String result = calculatorTool.calculate("PI * 2");
        assertTrue(result.startsWith("6.28318"));
    }

    @Test
    void testCalculate_MathPI() {
        String result = calculatorTool.calculate("Math.PI * 2");
        assertTrue(result.startsWith("6.28318"));
    }

    @Test
    void testCalculate_E() {
        String result = calculatorTool.calculate("E");
        assertTrue(result.startsWith("2.71828"));
    }

    // === SafeMathParser Security - Injection Attempts ===

    @ParameterizedTest
    @ValueSource(strings = {"java.lang.System.exit(0)", "import java.io.*;", "function() { return 1; }", "new ProcessBuilder('ls').start()",
            "this.constructor.constructor('return process')()", "eval('1+1')", "Runtime.getRuntime()", "var x = 1", "System.exit(0)", "print(1)",
            "fetch('http://evil.com')"})
    void testCalculate_RejectsUnsafeExpressions(String expression) {
        String result = calculatorTool.calculate(expression);
        assertTrue(result.startsWith("Error"), "Should reject unsafe expression: " + expression);
    }

    @Test
    void testCalculate_UnknownFunction() {
        String result = calculatorTool.calculate("random()");
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testCalculate_EmptyExpression() {
        String result = calculatorTool.calculate("");
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testCalculate_NullExpression() {
        String result = calculatorTool.calculate(null);
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testCalculate_OnlyWhitespace() {
        String result = calculatorTool.calculate("   ");
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testCalculate_InvalidExpression() {
        String result = calculatorTool.calculate("2 + * 2");
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testCalculate_ChainedExponentiation_RightAssociative() {
        // 2^3^2 should be 2^(3^2) = 2^9 = 512, NOT (2^3)^2 = 64
        assertEquals("512", calculatorTool.calculate("2 ^ 3 ^ 2"));
    }

    @Test
    void testCalculate_UnmatchedParenthesis() {
        String result = calculatorTool.calculate("(2 + 3");
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testCalculate_TrailingOperator() {
        // "2 +" leaves an unfinished expression
        String result = calculatorTool.calculate("2 +");
        assertTrue(result.startsWith("Error"));
    }

    // === SafeMathParser inner class direct tests ===

    @Test
    void testSafeMathParser_BasicArithmetic() {
        assertEquals(10.0, new CalculatorTool.SafeMathParser("3 + 7").parse(), 0.0001);
        assertEquals(-4.0, new CalculatorTool.SafeMathParser("3 - 7").parse(), 0.0001);
        assertEquals(21.0, new CalculatorTool.SafeMathParser("3 * 7").parse(), 0.0001);
        assertEquals(2.5, new CalculatorTool.SafeMathParser("5 / 2").parse(), 0.0001);
    }

    @Test
    void testSafeMathParser_OperatorPrecedence() {
        // Multiplication before addition
        assertEquals(11.0, new CalculatorTool.SafeMathParser("2 + 3 * 3").parse(), 0.0001);
        // Power before multiplication
        assertEquals(24.0, new CalculatorTool.SafeMathParser("3 * 2 ^ 3").parse(), 0.0001);
    }

    @Test
    void testSafeMathParser_UnaryMinus() {
        assertEquals(-5.0, new CalculatorTool.SafeMathParser("-5").parse(), 0.0001);
        assertEquals(5.0, new CalculatorTool.SafeMathParser("-(-5)").parse(), 0.0001);
    }

    @Test
    void testSafeMathParser_FunctionInExpression() {
        assertEquals(6.0, new CalculatorTool.SafeMathParser("sqrt(4) + 4").parse(), 0.0001);
        assertEquals(125.0, new CalculatorTool.SafeMathParser("pow(5, 3)").parse(), 0.0001);
    }

    @Test
    void testSafeMathParser_ThrowsOnUnknownFunction() {
        assertThrows(IllegalArgumentException.class, () -> new CalculatorTool.SafeMathParser("eval(1)").parse());
    }

    @Test
    void testSafeMathParser_ThrowsOnExtraChars() {
        assertThrows(IllegalArgumentException.class, () -> new CalculatorTool.SafeMathParser("2 + 2 abc").parse());
    }

    // === Unit Conversion Tests ===

    @ParameterizedTest
    @CsvSource({"0, celsius, fahrenheit", "100, celsius, fahrenheit", "32, fahrenheit, celsius", "212, fahrenheit, celsius", "0, celsius, kelvin",
            "273.15, kelvin, celsius"})
    void testConvertUnits_Temperature(double value, String fromUnit, String toUnit) {
        String result = calculatorTool.convertUnits(value, fromUnit, toUnit);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"), "Conversion should not error: " + result);
    }

    @ParameterizedTest
    @CsvSource({"1, km, miles", "1, miles, km", "1, m, feet", "1, feet, m"})
    void testConvertUnits_Distance(double value, String fromUnit, String toUnit) {
        String result = calculatorTool.convertUnits(value, fromUnit, toUnit);
        assertFalse(result.startsWith("Error"));
    }

    @ParameterizedTest
    @CsvSource({"1, kg, lb", "1, lb, kg"})
    void testConvertUnits_Weight(double value, String fromUnit, String toUnit) {
        String result = calculatorTool.convertUnits(value, fromUnit, toUnit);
        assertFalse(result.startsWith("Error"));
    }

    @ParameterizedTest
    @CsvSource({"1, liters, gallons", "1, gallons, liters"})
    void testConvertUnits_Volume(double value, String fromUnit, String toUnit) {
        String result = calculatorTool.convertUnits(value, fromUnit, toUnit);
        assertFalse(result.startsWith("Error"));
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
        assertFalse(result1.startsWith("Error"));
        assertFalse(result2.startsWith("Error"));
    }

    @Test
    void testConvertUnits_FreezingPointAccuracy() {
        String result = calculatorTool.convertUnits(0, "celsius", "fahrenheit");
        // Locale-independent check: result contains "32" and "fahrenheit"
        assertTrue(result.contains("32") && result.contains("fahrenheit"), "Expected 32 fahrenheit in: " + result);
    }

    @Test
    void testConvertUnits_BoilingPointAccuracy() {
        String result = calculatorTool.convertUnits(100, "celsius", "fahrenheit");
        // Locale-independent check: result contains "212" and "fahrenheit"
        assertTrue(result.contains("212") && result.contains("fahrenheit"), "Expected 212 fahrenheit in: " + result);
    }
}
