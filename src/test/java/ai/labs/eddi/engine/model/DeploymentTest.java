package ai.labs.eddi.engine.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // --- Environment ---

    @Test
    void environment_fromString_production() {
        assertEquals(Deployment.Environment.production,
                Deployment.Environment.fromString("production"));
    }

    @Test
    void environment_fromString_test() {
        assertEquals(Deployment.Environment.test,
                Deployment.Environment.fromString("test"));
    }

    @Test
    void environment_fromString_null_defaultsToProduction() {
        assertEquals(Deployment.Environment.production,
                Deployment.Environment.fromString(null));
    }

    @Test
    void environment_fromString_unknown_defaultsToProduction() {
        assertEquals(Deployment.Environment.production,
                Deployment.Environment.fromString("staging"));
    }

    @Test
    void environment_fromString_legacyUnrestricted_mapsToProduction() {
        assertEquals(Deployment.Environment.production,
                Deployment.Environment.fromString("unrestricted"));
    }

    @Test
    void environment_fromString_legacyRestricted_mapsToProduction() {
        assertEquals(Deployment.Environment.production,
                Deployment.Environment.fromString("restricted"));
    }

    @Test
    void environment_toValue_returnsName() {
        assertEquals("production", Deployment.Environment.production.toValue());
        assertEquals("test", Deployment.Environment.test.toValue());
    }

    @Test
    void environment_jacksonRoundTrip() throws Exception {
        String json = mapper.writeValueAsString(Deployment.Environment.production);
        assertEquals("\"production\"", json);

        Deployment.Environment deserialized = mapper.readValue(json, Deployment.Environment.class);
        assertEquals(Deployment.Environment.production, deserialized);
    }

    @Test
    void environment_jacksonDeserialize_legacy() throws Exception {
        assertEquals(Deployment.Environment.production,
                mapper.readValue("\"unrestricted\"", Deployment.Environment.class));
    }

    // --- Status ---

    @Test
    void status_allValuesExist() {
        assertNotNull(Deployment.Status.READY);
        assertNotNull(Deployment.Status.IN_PROGRESS);
        assertNotNull(Deployment.Status.NOT_FOUND);
        assertNotNull(Deployment.Status.ERROR);
        assertEquals(4, Deployment.Status.values().length);
    }
}
