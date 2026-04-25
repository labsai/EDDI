package ai.labs.eddi.modules.rules.impl.conditions;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DependencyTest {

    @Test
    void id() {
        assertEquals("dependency", new Dependency().getId());
    }

    @Test
    void defaultConfigs_hasReferenceKey() {
        var dep = new Dependency();
        var configs = dep.getConfigs();
        assertTrue(configs.containsKey("reference"));
        assertNull(configs.get("reference"));
    }

    @Test
    void setConfigs_setsReference() {
        var dep = new Dependency();
        dep.setConfigs(Map.of("reference", "greetingRule"));

        assertEquals("greetingRule", dep.getConfigs().get("reference"));
    }

    @Test
    void setConfigs_null_noOp() {
        assertDoesNotThrow(() -> new Dependency().setConfigs(null));
    }

    @Test
    void setConfigs_empty_noOp() {
        assertDoesNotThrow(() -> new Dependency().setConfigs(Collections.emptyMap()));
    }

    @Test
    void clone_preservesReference() {
        var dep = new Dependency();
        dep.setConfigs(Map.of("reference", "testRule"));

        var cloned = dep.clone();
        assertNotSame(dep, cloned);
        assertEquals("dependency", cloned.getId());
        assertEquals("testRule", cloned.getConfigs().get("reference"));
    }
}
