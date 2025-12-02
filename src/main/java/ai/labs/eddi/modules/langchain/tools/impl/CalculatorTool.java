package ai.labs.eddi.modules.langchain.tools.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculator tool for performing mathematical operations.
 * Uses JavaScript engine for safe expression evaluation.
 */
@ApplicationScoped
public class CalculatorTool {
    private static final Logger LOGGER = Logger.getLogger(CalculatorTool.class);
    private final ScriptEngine engine;

    public CalculatorTool() {
        ScriptEngineManager manager = new ScriptEngineManager();
        this.engine = manager.getEngineByName("javascript");
    }

    @Tool("Performs mathematical calculations. Supports basic operations (+, -, *, /), powers (Math.pow), square roots (Math.sqrt), trigonometry (Math.sin, Math.cos, Math.tan), and more. Returns the numeric result.")
    public String calculate(
            @P("Mathematical expression to evaluate (e.g., '2 + 2', 'Math.sqrt(16)', '(10 * 5) / 2')")
            String expression) {

        try {
            LOGGER.debug("Calculating expression: " + expression);

            // Sanitize input - only allow mathematical expressions
            if (!isSafeExpression(expression)) {
                return "Error: Invalid expression. Only mathematical operations are allowed.";
            }

            Object result = engine.eval(expression);

            if (result instanceof Number) {
                // Round to reasonable precision
                BigDecimal decimal = new BigDecimal(result.toString());
                decimal = decimal.setScale(10, RoundingMode.HALF_UP);
                decimal = decimal.stripTrailingZeros();

                String resultStr = decimal.toPlainString();
                LOGGER.info("Calculation result: " + expression + " = " + resultStr);
                return resultStr;
            } else {
                return result.toString();
            }

        } catch (ScriptException e) {
            LOGGER.error("Calculation error: " + e.getMessage());
            return "Error: Could not evaluate expression - " + e.getMessage();
        } catch (Exception e) {
            LOGGER.error("Unexpected calculation error", e);
            return "Error: An unexpected error occurred during calculation.";
        }
    }

    /**
     * Validates that the expression only contains safe mathematical operations
     */
    private boolean isSafeExpression(String expression) {
        // Remove whitespace for checking
        String cleaned = expression.replaceAll("\\s+", "");

        // Check for dangerous patterns
        if (cleaned.contains("import") ||
            cleaned.contains("java.") ||
            cleaned.contains("eval") ||
            cleaned.contains("function") ||
            cleaned.contains("var ") ||
            cleaned.contains("let ") ||
            cleaned.contains("const ") ||
            cleaned.contains("System") ||
            cleaned.contains("Runtime")) {
            return false;
        }

        // Only allow numbers, operators, Math functions, and parentheses
        return cleaned.matches("[0-9+\\-*/.(),MathsqrtpowabsceilflooroundminmaxsincostanatanlogexpPI\\s]+");
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
}

