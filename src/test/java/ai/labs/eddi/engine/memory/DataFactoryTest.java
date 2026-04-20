package ai.labs.eddi.engine.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DataFactory} — simple factory for creating IData instances.
 */
@DisplayName("DataFactory")
class DataFactoryTest {

    private DataFactory factory;

    @BeforeEach
    void setUp() {
        factory = new DataFactory();
    }

    @Test
    @DisplayName("creates data with key and value")
    void createDataKeyValue() {
        IData<String> data = factory.createData("input", "hello");
        assertEquals("input", data.getKey());
        assertEquals("hello", data.getResult());
        assertFalse(data.isPublic());
    }

    @Test
    @DisplayName("creates public data")
    void createPublicData() {
        IData<String> data = factory.createData("output", "world", true);
        assertEquals("output", data.getKey());
        assertEquals("world", data.getResult());
        assertTrue(data.isPublic());
    }

    @Test
    @DisplayName("creates private data explicitly")
    void createPrivateData() {
        IData<String> data = factory.createData("internal", "secret", false);
        assertFalse(data.isPublic());
    }

    @Test
    @DisplayName("creates data with possible values")
    void createDataWithPossibleValues() {
        List<String> possibleValues = List.of("a", "b", "c");
        IData<String> data = factory.createData("choices", "a", possibleValues);
        assertEquals("choices", data.getKey());
        assertEquals("a", data.getResult());
        assertEquals(possibleValues, data.getPossibleResults());
    }

    @Test
    @DisplayName("creates data with integer value")
    void createIntegerData() {
        IData<Integer> data = factory.createData("count", 42);
        assertEquals(42, data.getResult());
    }

    @Test
    @DisplayName("creates data with list value")
    void createListData() {
        List<String> actions = List.of("greet", "farewell");
        IData<List<String>> data = factory.createData("actions", actions);
        assertEquals(actions, data.getResult());
    }

    @Test
    @DisplayName("creates data with null value")
    void createNullValueData() {
        IData<String> data = factory.createData("key", null);
        assertNull(data.getResult());
    }
}
