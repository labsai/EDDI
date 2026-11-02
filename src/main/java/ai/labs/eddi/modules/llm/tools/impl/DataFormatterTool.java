/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Data formatting tool for parsing and converting between different data
 * formats.
 */
@ApplicationScoped
public class DataFormatterTool {
    private static final Logger LOGGER = Logger.getLogger(DataFormatterTool.class);
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final XmlMapper xmlMapper = new XmlMapper();
    private final CsvMapper csvMapper = new CsvMapper();

    /**
     * Most characters {@link #csvToJson} produces. Every row repeats every header
     * name, so a long header over many short rows multiplied a modest argument into
     * a far larger string in the shared heap; serialization stops at this budget
     * instead of materializing the whole result first.
     */
    static final int MAX_CSV_JSON_CHARS = 1_000_000;

    /** Most data rows {@link #csvToJson} reads. */
    static final int MAX_CSV_ROWS = 10_000;

    @Tool("Validates and formats JSON data. Returns formatted JSON or error message.")
    public String formatJson(@P("The JSON document, as text") String jsonString) {

        try {
            JsonNode jsonNode = jsonMapper.readTree(jsonString);
            String formatted = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
            LOGGER.debug("JSON formatted successfully");
            return "Valid JSON:\n" + formatted;

        } catch (Exception e) {
            LOGGER.debugf("JSON validation error: %s", sanitize(e.getMessage()));
            return "Error: Invalid JSON - " + e.getMessage();
        }
    }

    @Tool("Converts JSON to XML format")
    public String jsonToXml(@P("The JSON document, as text") String jsonString) {

        try {
            JsonNode jsonNode = jsonMapper.readTree(jsonString);
            String xml = xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
            LOGGER.debug("JSON converted to XML successfully");
            return xml;

        } catch (Exception e) {
            LOGGER.debugf("JSON to XML conversion error: %s", sanitize(e.getMessage()));
            return "Error: Could not convert JSON to XML - " + e.getMessage();
        }
    }

    @Tool("Converts XML to JSON format")
    public String xmlToJson(@P("The XML document, as text") String xmlString) {

        try {
            JsonNode jsonNode = xmlMapper.readTree(xmlString);
            String json = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
            LOGGER.debug("XML converted to JSON successfully");
            return json;

        } catch (Exception e) {
            LOGGER.debugf("XML to JSON conversion error: %s", sanitize(e.getMessage()));
            return "Error: Could not convert XML to JSON - " + e.getMessage();
        }
    }

    @Tool("Parses CSV data and converts it to JSON format")
    public String csvToJson(@P("CSV text; the first line holds the column names") String csvString) {

        try {
            CsvSchema schema = CsvSchema.emptySchema().withHeader();
            List<Object> data = new ArrayList<>();
            try (var iterator = csvMapper.readerFor(Map.class).with(schema).readValues(csvString)) {
                while (iterator.hasNext()) {
                    if (data.size() >= MAX_CSV_ROWS) {
                        return "Error: Could not convert CSV to JSON - more than " + MAX_CSV_ROWS + " rows";
                    }
                    data.add(iterator.next());
                }
            }

            var out = new BoundedWriter(MAX_CSV_JSON_CHARS);
            try {
                jsonMapper.writerWithDefaultPrettyPrinter().writeValue(out, data);
            } catch (BoundedWriter.BudgetExceededException e) {
                return "Error: Could not convert CSV to JSON - the JSON would exceed " + MAX_CSV_JSON_CHARS + " characters";
            }
            LOGGER.debug("CSV converted to JSON successfully");
            return out.toString();

        } catch (Exception e) {
            LOGGER.debugf("CSV to JSON conversion error: %s", sanitize(e.getMessage()));
            return "Error: Could not convert CSV to JSON - " + e.getMessage();
        }
    }

    @Tool("Extracts a value from JSON using a JSONPath-like expression")
    public String extractJsonValue(@P("The JSON document, as text") String jsonString,
                                   @P("Dot-separated path to the value, e.g. 'user.address.city' or 'items[0].name'") String path) {

        try {
            if (path == null || path.isBlank()) {
                return "Error: path must not be empty";
            }
            JsonNode valueNode = jsonMapper.readTree(jsonString);

            // Simple path traversal (for complex paths, use JSONPath library). Every
            // step is checked: a missing field used to dereference null and hand the
            // model "Could not extract value - null".
            String[] parts = path.split("\\.");
            for (String part : parts) {
                int open = part.indexOf('[');
                if (open >= 0) {
                    int close = part.indexOf(']', open);
                    if (close != part.length() - 1) {
                        return "Error: Malformed array index in path segment '" + part + "' - use name[index]";
                    }
                    String arrayName = part.substring(0, open);
                    int index;
                    try {
                        index = Integer.parseInt(part.substring(open + 1, close).trim());
                    } catch (NumberFormatException e) {
                        return "Error: Array index in path segment '" + part + "' is not a number";
                    }
                    JsonNode array = arrayName.isEmpty() ? valueNode : valueNode.get(arrayName);
                    if (array == null) {
                        return "Error: Path not found - " + path + " (no field '" + arrayName + "')";
                    }
                    if (!array.isArray()) {
                        return "Error: Path not found - " + path + " ('" + arrayName + "' is not an array)";
                    }
                    valueNode = array.get(index);
                } else {
                    valueNode = valueNode.get(part);
                }

                if (valueNode == null) {
                    return "Error: Path not found - " + path;
                }
            }

            String result = valueNode.isTextual() ? valueNode.asText() : valueNode.toString();
            LOGGER.debugf("Extracted value from JSON path: %s", sanitize(path));
            return result;

        } catch (Exception e) {
            LOGGER.debugf("JSON value extraction error: %s", sanitize(e.getMessage()));
            return "Error: Could not extract value - " + e.getMessage();
        }
    }

    @Tool("Validates XML against basic well-formedness rules")
    public String validateXml(@P("The XML document, as text") String xmlString) {

        try {
            xmlMapper.readTree(xmlString);
            LOGGER.debug("XML validated successfully");
            return "Valid XML: The XML is well-formed.";

        } catch (Exception e) {
            LOGGER.debugf("XML validation error: %s", sanitize(e.getMessage()));
            return "Error: Invalid XML - " + e.getMessage();
        }
    }

    @Tool("Minifies JSON by removing whitespace and formatting")
    public String minifyJson(@P("The JSON document, as text") String jsonString) {

        try {
            JsonNode jsonNode = jsonMapper.readTree(jsonString);
            String minified = jsonMapper.writeValueAsString(jsonNode);
            LOGGER.debug("JSON minified successfully");
            return minified;

        } catch (Exception e) {
            LOGGER.debugf("JSON minification error: %s", sanitize(e.getMessage()));
            return "Error: Could not minify JSON - " + e.getMessage();
        }
    }

    /**
     * A {@link Writer} into memory that refuses to grow past a character budget.
     */
    static final class BoundedWriter extends Writer {
        private final StringBuilder buffer = new StringBuilder();
        private final int maxChars;

        BoundedWriter(int maxChars) {
            this.maxChars = maxChars;
        }

        @Override
        public void write(char[] chars, int offset, int length) throws IOException {
            if (buffer.length() + length > maxChars) {
                throw new BudgetExceededException();
            }
            buffer.append(chars, offset, length);
        }

        @Override
        public void flush() {
            // in memory: nothing to flush
        }

        @Override
        public void close() {
            // in memory: nothing to release
        }

        @Override
        public String toString() {
            return buffer.toString();
        }

        /** Thrown when a write would exceed the budget. */
        static final class BudgetExceededException extends IOException {
            BudgetExceededException() {
                super("output budget exceeded");
            }
        }
    }
}
