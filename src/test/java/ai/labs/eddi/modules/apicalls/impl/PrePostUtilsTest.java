package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.HttpCodeValidator;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrePostUtilsTest {

    private PrePostUtils prePostUtils;

    @BeforeEach
    void setUp() {
        prePostUtils = new PrePostUtils(
                mock(IJsonSerialization.class),
                mock(IMemoryItemConverter.class),
                mock(ITemplatingEngine.class),
                mock(IDataFactory.class));
    }

    // ==================== verifyHttpCode ====================

    @Test
    void verifyHttpCode_nullValidator_defaultAllows200() {
        assertTrue(prePostUtils.verifyHttpCode(null, 200));
    }

    @Test
    void verifyHttpCode_nullValidator_defaultAllows201() {
        assertTrue(prePostUtils.verifyHttpCode(null, 201));
    }

    @Test
    void verifyHttpCode_nullValidator_defaultRejects204() {
        // DEFAULT only allows 200, 201
        assertFalse(prePostUtils.verifyHttpCode(null, 204));
    }

    @Test
    void verifyHttpCode_nullValidator_defaultRejects500() {
        assertFalse(prePostUtils.verifyHttpCode(null, 500));
    }

    @Test
    void verifyHttpCode_customRunOnCodes() {
        var validator = new HttpCodeValidator(List.of(200, 404), List.of());
        assertTrue(prePostUtils.verifyHttpCode(validator, 200));
        assertTrue(prePostUtils.verifyHttpCode(validator, 404));
        assertFalse(prePostUtils.verifyHttpCode(validator, 500));
    }

    @Test
    void verifyHttpCode_skipOverridesRun() {
        var validator = new HttpCodeValidator(List.of(200, 201, 202), List.of(201));
        assertTrue(prePostUtils.verifyHttpCode(validator, 200));
        assertFalse(prePostUtils.verifyHttpCode(validator, 201)); // skipped
        assertTrue(prePostUtils.verifyHttpCode(validator, 202));
    }

    @Test
    void verifyHttpCode_nullRunOnCodes_usesDefault() {
        var validator = new HttpCodeValidator();
        validator.setRunOnHttpCode(null);
        validator.setSkipOnHttpCode(List.of());
        // Falls back to DEFAULT runOnHttpCode = [200, 201]
        assertTrue(prePostUtils.verifyHttpCode(validator, 200));
    }

    @Test
    void verifyHttpCode_nullSkipOnCodes_usesDefault() {
        var validator = new HttpCodeValidator();
        validator.setRunOnHttpCode(List.of(200));
        validator.setSkipOnHttpCode(null);
        assertTrue(prePostUtils.verifyHttpCode(validator, 200));
    }

    @Test
    void verifyHttpCode_codeNotInRunList_returnsFalse() {
        var validator = new HttpCodeValidator(List.of(200), List.of());
        assertFalse(prePostUtils.verifyHttpCode(validator, 500));
        assertFalse(prePostUtils.verifyHttpCode(validator, 404));
    }
}
