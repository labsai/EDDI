package ai.labs.eddi.configs.apicalls.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HttpCodeValidatorTest {

    @Test
    void default_runsOn200And201() {
        assertTrue(HttpCodeValidator.DEFAULT.getRunOnHttpCode().contains(200));
        assertTrue(HttpCodeValidator.DEFAULT.getRunOnHttpCode().contains(201));
    }

    @Test
    void default_skipsOn400Plus() {
        assertTrue(HttpCodeValidator.DEFAULT.getSkipOnHttpCode().contains(400));
        assertTrue(HttpCodeValidator.DEFAULT.getSkipOnHttpCode().contains(500));
    }

    @Test
    void default_skipsOn0() {
        // 0 means "no HTTP code" (e.g. connection failure)
        assertTrue(HttpCodeValidator.DEFAULT.getSkipOnHttpCode().contains(0));
    }

    @Test
    void constructor_setsFields() {
        var v = new HttpCodeValidator(List.of(200), List.of(500));
        assertEquals(List.of(200), v.getRunOnHttpCode());
        assertEquals(List.of(500), v.getSkipOnHttpCode());
    }

    @Test
    void defaultConstructor_nullFields() {
        var v = new HttpCodeValidator();
        assertNull(v.getRunOnHttpCode());
        assertNull(v.getSkipOnHttpCode());
    }

    @Test
    void setters() {
        var v = new HttpCodeValidator();
        v.setRunOnHttpCode(List.of(201));
        v.setSkipOnHttpCode(List.of(400));
        assertEquals(List.of(201), v.getRunOnHttpCode());
        assertEquals(List.of(400), v.getSkipOnHttpCode());
    }
}
