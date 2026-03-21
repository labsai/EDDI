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

import java.util.List;
import java.util.Map;

/**
 * Data formatting tool for parsing and converting between different data formats.
 */
@ApplicationScoped
public class DataFormatterTool {
    private static final Logger LOGGER = Logger.getLogger(DataFormatterTool.class);
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final XmlMapper xmlMapper = new XmlMapper();
    private final CsvMapper csvMapper = new CsvMapper();

    @Tool("Validates and formats JSON data. Returns formatted JSON or error message.")
    public String formatJson(
            @P("jsonString") String jsonString) {

        try {
            JsonNode jsonNode = jsonMapper.readTree(jsonString);
            String formatted = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
            LOGGER.debug("JSON formatted successfully");
            return "Valid JSON:\n" + formatted;

        } catch (Exception e) {
            LOGGER.error("JSON validation error: " + e.getMessage());
            return "Error: Invalid JSON - " + e.getMessage();
        }
    }

    @Tool("Converts JSON to XML format")
    public String jsonToXml(
            @P("jsonString") String jsonString) {

        try {
            JsonNode jsonNode = jsonMapper.readTree(jsonString);
            String xml = xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
            LOGGER.debug("JSON converted to XML successfully");
            return xml;

        } catch (Exception e) {
            LOGGER.error("JSON to XML conversion error: " + e.getMessage());
            return "Error: Could not convert JSON to XML - " + e.getMessage();
        }
    }

    @Tool("Converts XML to JSON format")
    public String xmlToJson(
            @P("xmlString") String xmlString) {

        try {
            JsonNode jsonNode = xmlMapper.readTree(xmlString);
            String json = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
            LOGGER.debug("XML converted to JSON successfully");
            return json;

        } catch (Exception e) {
            LOGGER.error("XML to JSON conversion error: " + e.getMessage());
            return "Error: Could not convert XML to JSON - " + e.getMessage();
        }
    }

    @Tool("Parses CSV data and converts it to JSON format")
    public String csvToJson(
            @P("csvString") String csvString) {

        try {
            CsvSchema schema = CsvSchema.emptySchema().withHeader();
            List<?> data;
            try (var iterator = csvMapper
                    .readerFor(Map.class)
                    .with(schema)
                    .readValues(csvString)) {
                data = iterator.readAll();
            }

            String json = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            LOGGER.debug("CSV converted to JSON successfully");
            return json;

        } catch (Exception e) {
            LOGGER.error("CSV to JSON conversion error: " + e.getMessage());
            return "Error: Could not convert CSV to JSON - " + e.getMessage();
        }
    }

    @Tool("Extracts a value from JSON using a JSONPath-like expression")
    public String extractJsonValue(
            @P("jsonString") String jsonString,
            @P("path") String path) {

        try {
            JsonNode valueNode = jsonMapper.readTree(jsonString);

            // Simple path traversal (for complex paths, use JSONPath library)
            String[] parts = path.split("\\.");
            for (String part : parts) {
                if (part.contains("[")) {
                    // Array access
                    String arrayName = part.substring(0, part.indexOf('['));
                    int index = Integer.parseInt(part.substring(part.indexOf('[') + 1, part.indexOf(']')));
                    valueNode = valueNode.get(arrayName).get(index);
                } else {
                    valueNode = valueNode.get(part);
                }

                if (valueNode == null) {
                    return "Error: Path not found - " + path;
                }
            }

            String result = valueNode.isTextual() ? valueNode.asText() : valueNode.toString();
            LOGGER.debug("Extracted value from JSON path: " + path);
            return result;

        } catch (Exception e) {
            LOGGER.error("JSON value extraction error: " + e.getMessage());
            return "Error: Could not extract value - " + e.getMessage();
        }
    }

    @Tool("Validates XML against basic well-formedness rules")
    public String validateXml(
            @P("xmlString") String xmlString) {

        try {
            xmlMapper.readTree(xmlString);
            LOGGER.debug("XML validated successfully");
            return "Valid XML: The XML is well-formed.";

        } catch (Exception e) {
            LOGGER.error("XML validation error: " + e.getMessage());
            return "Error: Invalid XML - " + e.getMessage();
        }
    }

    @Tool("Minifies JSON by removing whitespace and formatting")
    public String minifyJson(
            @P("jsonString") String jsonString) {

        try {
            JsonNode jsonNode = jsonMapper.readTree(jsonString);
            String minified = jsonMapper.writeValueAsString(jsonNode);
            LOGGER.debug("JSON minified successfully");
            return minified;

        } catch (Exception e) {
            LOGGER.error("JSON minification error: " + e.getMessage());
            return "Error: Could not minify JSON - " + e.getMessage();
        }
    }
}

