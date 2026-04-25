package ai.labs.eddi.modules.llm.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CustomToolConfigurationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaults() {
        var config = new CustomToolConfiguration();
        assertNull(config.getName());
        assertNull(config.getType());
        assertFalse(config.isRequiresAuth());
        assertEquals(0.0, config.getCostPerExecution());
        assertNull(config.getCacheTtlMs());
        assertNull(config.getRateLimit());
    }

    @Test
    void constructor_setsAll() {
        var param = new CustomToolConfiguration.ToolParameter(
                "city", "City name", "string", true, "Vienna");
        var config = new CustomToolConfiguration(
                "weather", "Get weather",
                CustomToolConfiguration.ToolType.HTTPCALL,
                List.of(param),
                Map.of("url", "https://api.weather.com"),
                true, 0.01, 60000L, 30);

        assertEquals("weather", config.getName());
        assertEquals("Get weather", config.getDescription());
        assertEquals(CustomToolConfiguration.ToolType.HTTPCALL, config.getType());
        assertEquals(1, config.getParameters().size());
        assertEquals("https://api.weather.com", config.getConfig().get("url"));
        assertTrue(config.isRequiresAuth());
        assertEquals(0.01, config.getCostPerExecution());
        assertEquals(60000L, config.getCacheTtlMs());
        assertEquals(30, config.getRateLimit());
    }

    @Test
    void setters() {
        var config = new CustomToolConfiguration();
        config.setName("tool1");
        config.setDescription("desc");
        config.setType(CustomToolConfiguration.ToolType.SCRIPT);
        config.setRequiresAuth(true);
        config.setCostPerExecution(0.05);
        config.setCacheTtlMs(30000L);
        config.setRateLimit(10);

        assertEquals("tool1", config.getName());
        assertEquals(CustomToolConfiguration.ToolType.SCRIPT, config.getType());
        assertTrue(config.isRequiresAuth());
    }

    @Test
    void toolType_allValues() {
        assertEquals(5, CustomToolConfiguration.ToolType.values().length);
        assertNotNull(CustomToolConfiguration.ToolType.HTTPCALL);
        assertNotNull(CustomToolConfiguration.ToolType.SCRIPT);
        assertNotNull(CustomToolConfiguration.ToolType.COMPOSITE);
        assertNotNull(CustomToolConfiguration.ToolType.DATABASE);
        assertNotNull(CustomToolConfiguration.ToolType.FILESYSTEM);
    }

    // --- ToolParameter ---

    @Test
    void toolParameter_constructor() {
        var param = new CustomToolConfiguration.ToolParameter(
                "query", "Search query", "string", true, null);
        assertEquals("query", param.getName());
        assertEquals("Search query", param.getDescription());
        assertEquals("string", param.getType());
        assertTrue(param.isRequired());
        assertNull(param.getDefaultValue());
    }

    @Test
    void toolParameter_setters() {
        var param = new CustomToolConfiguration.ToolParameter();
        param.setName("count");
        param.setDescription("Number of results");
        param.setType("number");
        param.setRequired(false);
        param.setDefaultValue(10);

        assertEquals("count", param.getName());
        assertEquals(10, param.getDefaultValue());
        assertFalse(param.isRequired());
    }

    @Test
    void toolParameter_equals() {
        var p1 = new CustomToolConfiguration.ToolParameter("n", "d", "string", true, null);
        var p2 = new CustomToolConfiguration.ToolParameter("n", "d", "string", true, null);
        assertEquals(p1, p2);
        assertEquals(p1.hashCode(), p2.hashCode());
    }

    @Test
    void toolParameter_notEquals() {
        var p1 = new CustomToolConfiguration.ToolParameter("n1", "d", "string", true, null);
        var p2 = new CustomToolConfiguration.ToolParameter("n2", "d", "string", true, null);
        assertNotEquals(p1, p2);
    }

    @Test
    void toolParameter_toString() {
        var param = new CustomToolConfiguration.ToolParameter("q", "desc", "string", true, null);
        assertTrue(param.toString().contains("q"));
    }

    // --- Equality ---

    @Test
    void equals_sameConfig() {
        var c1 = new CustomToolConfiguration("t", "d",
                CustomToolConfiguration.ToolType.HTTPCALL, null, null, false, 0.0, null, null);
        var c2 = new CustomToolConfiguration("t", "d",
                CustomToolConfiguration.ToolType.HTTPCALL, null, null, false, 0.0, null, null);
        assertEquals(c1, c2);
        assertEquals(c1.hashCode(), c2.hashCode());
    }

    @Test
    void equals_differentName() {
        var c1 = new CustomToolConfiguration("a", "d",
                CustomToolConfiguration.ToolType.HTTPCALL, null, null, false, 0.0, null, null);
        var c2 = new CustomToolConfiguration("b", "d",
                CustomToolConfiguration.ToolType.HTTPCALL, null, null, false, 0.0, null, null);
        assertNotEquals(c1, c2);
    }

    @Test
    void toString_containsName() {
        var config = new CustomToolConfiguration();
        config.setName("myTool");
        assertTrue(config.toString().contains("myTool"));
    }

    @Test
    void jacksonRoundTrip() throws Exception {
        var config = new CustomToolConfiguration("test", "Test tool",
                CustomToolConfiguration.ToolType.SCRIPT, null, null, false, 0.0, null, null);

        String json = mapper.writeValueAsString(config);
        var restored = mapper.readValue(json, CustomToolConfiguration.class);

        assertEquals("test", restored.getName());
        assertEquals(CustomToolConfiguration.ToolType.SCRIPT, restored.getType());
    }
}
