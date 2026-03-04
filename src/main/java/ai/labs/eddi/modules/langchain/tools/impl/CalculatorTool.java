package ai.labs.eddi.modules.langchain.tools.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/**
 * Calculator tool for performing mathematical operations.
 * Uses a safe recursive descent parser for expression evaluation.
 * No external scripting engine required - pure Java implementation.
 */
@ApplicationScoped
public class CalculatorTool {
    private static final Logger LOGGER = Logger.getLogger(CalculatorTool.class);

    @Tool("Performs mathematical calculations. Supports basic operations (+, -, *, /, ^, %), " +
          "functions (sqrt, pow, abs, ceil, floor, round, min, max, sin, cos, tan, atan, log, exp), " +
          "and constants (PI, E). Returns the numeric result.")
    public String calculate(
            @P("Mathematical expression to evaluate (e.g., '2 + 2', 'sqrt(16)', '(10 * 5) / 2')")
            String expression) {

        try {
            LOGGER.debug("Calculating expression: " + expression);

            if (expression == null || expression.isBlank()) {
                return "Error: Expression must not be empty.";
            }

            double result = new SafeMathParser(expression).parse();

            // Handle special values
            if (Double.isNaN(result)) {
                return "NaN";
            }
            if (Double.isInfinite(result)) {
                return result > 0 ? "Infinity" : "-Infinity";
            }

            // Round to reasonable precision
            BigDecimal decimal = BigDecimal.valueOf(result);
            decimal = decimal.setScale(10, RoundingMode.HALF_UP);
            decimal = decimal.stripTrailingZeros();

            String resultStr = decimal.toPlainString();
            LOGGER.info("Calculation result: " + expression + " = " + resultStr);
            return resultStr;

        } catch (IllegalArgumentException e) {
            LOGGER.error("Calculation error: " + e.getMessage());
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            LOGGER.error("Unexpected calculation error", e);
            return "Error: An unexpected error occurred during calculation.";
        }
    }

    @Tool("Converts between different units of measurement")
    public String convertUnits(
            @P("Value to convert") double value,
            @P("Source unit (e.g., 'celsius', 'km', 'lb')") String fromUnit,
            @P("Target unit (e.g., 'fahrenheit', 'miles', 'kg')") String toUnit) {

        try {
            double result = performConversion(value, fromUnit.toLowerCase(), toUnit.toLowerCase());
            return String.format("%.4f %s = %.4f %s", value, fromUnit, result, toUnit);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }

    private double performConversion(double value, String fromUnit, String toUnit) {
        // Temperature conversions
        if (fromUnit.equals("celsius") && toUnit.equals("fahrenheit")) {
            return (value * 9.0 / 5.0) + 32;
        }
        if (fromUnit.equals("fahrenheit") && toUnit.equals("celsius")) {
            return (value - 32) * 5.0 / 9.0;
        }
        if (fromUnit.equals("celsius") && toUnit.equals("kelvin")) {
            return value + 273.15;
        }
        if (fromUnit.equals("kelvin") && toUnit.equals("celsius")) {
            return value - 273.15;
        }

        // Distance conversions
        if (fromUnit.equals("km") && toUnit.equals("miles")) {
            return value * 0.621371;
        }
        if (fromUnit.equals("miles") && toUnit.equals("km")) {
            return value * 1.60934;
        }
        if (fromUnit.equals("m") && toUnit.equals("feet")) {
            return value * 3.28084;
        }
        if (fromUnit.equals("feet") && toUnit.equals("m")) {
            return value * 0.3048;
        }

        // Weight conversions
        if (fromUnit.equals("kg") && toUnit.equals("lb")) {
            return value * 2.20462;
        }
        if (fromUnit.equals("lb") && toUnit.equals("kg")) {
            return value * 0.453592;
        }

        // Volume conversions
        if (fromUnit.equals("liters") && toUnit.equals("gallons")) {
            return value * 0.264172;
        }
        if (fromUnit.equals("gallons") && toUnit.equals("liters")) {
            return value * 3.78541;
        }

        throw new IllegalArgumentException("Unsupported conversion: " + fromUnit + " to " + toUnit);
    }

    /**
     * Safe recursive descent math expression parser.
     * Supports: +, -, *, /, %, ^, parentheses, Math functions, and constants.
     * No code injection possible - only evaluates mathematical expressions.
     *
     * Grammar:
     *   expression := term (('+' | '-') term)*
     *   term       := power (('*' | '/' | '%') power)*
     *   power      := unary ('^' power)?
     *   unary      := '-' unary | '+' unary | primary
     *   primary    := NUMBER | CONSTANT | FUNCTION '(' args ')' | '(' expression ')'
     *   args       := expression (',' expression)*
     */
    static class SafeMathParser {
        private final String expression;
        private int pos;

        SafeMathParser(String expression) {
            this.expression = expression.trim();
            this.pos = 0;
        }

        double parse() {
            double result = parseExpression();
            skipWhitespace();
            if (pos < expression.length()) {
                throw new IllegalArgumentException(
                        "Unexpected character '" + expression.charAt(pos) + "' at position " + pos);
            }
            return result;
        }

        private double parseExpression() {
            double result = parseTerm();
            skipWhitespace();
            while (pos < expression.length()) {
                char c = expression.charAt(pos);
                if (c == '+') {
                    pos++;
                    result += parseTerm();
                } else if (c == '-') {
                    pos++;
                    result -= parseTerm();
                } else {
                    break;
                }
                skipWhitespace();
            }
            return result;
        }

        private double parseTerm() {
            double result = parsePower();
            skipWhitespace();
            while (pos < expression.length()) {
                char c = expression.charAt(pos);
                if (c == '*') {
                    pos++;
                    result *= parsePower();
                } else if (c == '/') {
                    pos++;
                    result /= parsePower();
                } else if (c == '%') {
                    pos++;
                    result %= parsePower();
                } else {
                    break;
                }
                skipWhitespace();
            }
            return result;
        }

        private double parsePower() {
            double base = parseUnary();
            skipWhitespace();
            if (pos < expression.length() && expression.charAt(pos) == '^') {
                pos++;
                double exponent = parsePower(); // Right-recursive for right-associativity
                return Math.pow(base, exponent);
            }
            return base;
        }

        private double parseUnary() {
            skipWhitespace();
            if (pos < expression.length()) {
                if (expression.charAt(pos) == '-') {
                    pos++;
                    return -parseUnary();
                }
                if (expression.charAt(pos) == '+') {
                    pos++;
                    return parseUnary();
                }
            }
            return parsePrimary();
        }

        private double parsePrimary() {
            skipWhitespace();
            if (pos >= expression.length()) {
                throw new IllegalArgumentException("Unexpected end of expression");
            }

            char c = expression.charAt(pos);

            // Number
            if (Character.isDigit(c) || c == '.') {
                return parseNumber();
            }

            // Parenthesized expression
            if (c == '(') {
                pos++;
                double result = parseExpression();
                expect(')');
                return result;
            }

            // Function or constant name
            String name = parseName();

            // Strip optional "Math." prefix
            if (name.startsWith("Math.")) {
                name = name.substring(5);
            }

            return switch (name) {
                // Constants
                case "PI" -> Math.PI;
                case "E" -> Math.E;

                // Single-argument functions
                case "sqrt" -> callOneArg(Math::sqrt);
                case "abs" -> callOneArg(Math::abs);
                case "ceil" -> callOneArg(Math::ceil);
                case "floor" -> callOneArg(Math::floor);
                case "round" -> callOneArg(v -> (double) Math.round(v));
                case "sin" -> callOneArg(Math::sin);
                case "cos" -> callOneArg(Math::cos);
                case "tan" -> callOneArg(Math::tan);
                case "atan" -> callOneArg(Math::atan);
                case "asin" -> callOneArg(Math::asin);
                case "acos" -> callOneArg(Math::acos);
                case "log" -> callOneArg(Math::log);
                case "log10" -> callOneArg(Math::log10);
                case "exp" -> callOneArg(Math::exp);
                case "signum", "sign" -> callOneArg(Math::signum);
                case "toRadians" -> callOneArg(Math::toRadians);
                case "toDegrees" -> callOneArg(Math::toDegrees);
                case "cbrt" -> callOneArg(Math::cbrt);

                // Two-argument functions
                case "pow" -> callTwoArgs(Math::pow);
                case "min" -> callTwoArgs(Math::min);
                case "max" -> callTwoArgs(Math::max);
                case "atan2" -> callTwoArgs(Math::atan2);

                default -> throw new IllegalArgumentException("Unknown function or constant: " + name);
            };
        }

        private double callOneArg(DoubleUnaryOperator fn) {
            expect('(');
            double arg = parseExpression();
            expect(')');
            return fn.applyAsDouble(arg);
        }

        private double callTwoArgs(DoubleBinaryOperator fn) {
            expect('(');
            double arg1 = parseExpression();
            expect(',');
            double arg2 = parseExpression();
            expect(')');
            return fn.applyAsDouble(arg1, arg2);
        }

        private double parseNumber() {
            int start = pos;
            while (pos < expression.length() &&
                    (Character.isDigit(expression.charAt(pos)) || expression.charAt(pos) == '.')) {
                pos++;
            }
            String numStr = expression.substring(start, pos);
            try {
                return Double.parseDouble(numStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number: " + numStr);
            }
        }

        private String parseName() {
            int start = pos;
            while (pos < expression.length() &&
                    (Character.isLetterOrDigit(expression.charAt(pos)) || expression.charAt(pos) == '.')) {
                pos++;
            }
            if (pos == start) {
                throw new IllegalArgumentException(
                        "Expected function name or constant at position " + pos);
            }
            return expression.substring(start, pos);
        }

        private void expect(char c) {
            skipWhitespace();
            if (pos >= expression.length() || expression.charAt(pos) != c) {
                throw new IllegalArgumentException(
                        "Expected '" + c + "' at position " + pos);
            }
            pos++;
        }

        private void skipWhitespace() {
            while (pos < expression.length() && Character.isWhitespace(expression.charAt(pos))) {
                pos++;
            }
        }
    }
}

