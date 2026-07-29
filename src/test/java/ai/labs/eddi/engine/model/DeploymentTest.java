/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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

    // --- Environment.parseStrict ---

    @Test
    void environment_parseStrict_nullOrBlank_defaultsToProduction() {
        assertEquals(Deployment.Environment.production, Deployment.Environment.parseStrict(null));
        assertEquals(Deployment.Environment.production, Deployment.Environment.parseStrict(""));
        assertEquals(Deployment.Environment.production, Deployment.Environment.parseStrict("   "));
    }

    @Test
    void environment_parseStrict_knownValues_areCaseAndWhitespaceInsensitive() {
        assertEquals(Deployment.Environment.production, Deployment.Environment.parseStrict("PRODUCTION"));
        assertEquals(Deployment.Environment.test, Deployment.Environment.parseStrict("  Test  "));
    }

    @Test
    void environment_parseStrict_legacyV5Names_mapToProduction() {
        assertEquals(Deployment.Environment.production, Deployment.Environment.parseStrict("unrestricted"));
        assertEquals(Deployment.Environment.production, Deployment.Environment.parseStrict("restricted"));
    }

    /**
     * An environment the platform does not know must never resolve to production —
     * a typo would otherwise deploy to / talk to the live environment.
     */
    @Test
    void environment_parseStrict_unknown_throwsNamingValidValues() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> Deployment.Environment.parseStrict("staging"));

        assertEquals("Unknown environment 'staging'. Valid values: production, test", exception.getMessage());
    }

    /**
     * Deliberate asymmetry: deserialization (persisted deployment documents, JSON
     * bodies, JAX-RS params) must stay lenient so one malformed stored value cannot
     * break reading the deployment table. Strictness lives in {@code parseStrict},
     * used by the call sites that act on the value.
     */
    @Test
    void environment_fromString_staysLenientWhileParseStrictRejects() {
        assertEquals(Deployment.Environment.production, Deployment.Environment.fromString("staging"));
        assertThrows(IllegalArgumentException.class, () -> Deployment.Environment.parseStrict("staging"));
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
