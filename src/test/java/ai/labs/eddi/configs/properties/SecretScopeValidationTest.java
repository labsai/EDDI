/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.HttpPostResponse;
import ai.labs.eddi.configs.apicalls.mongo.ApiCallsStore;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.propertysetter.mongo.PropertySetterStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.modules.properties.model.SetOnActions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * C2b — a {@code scope: "secret"} instruction that can never be vaulted is
 * rejected when the configuration is saved, not stored as plaintext when a
 * conversation runs it.
 */
class SecretScopeValidationTest {

    private static PropertyInstruction secret(String name) {
        var instruction = new PropertyInstruction();
        instruction.setName(name);
        instruction.setScope(Scope.secret);
        return instruction;
    }

    @Test
    @DisplayName("valueString and fromObjectPath under scope secret are accepted")
    void vaultableShapesAccepted() {
        var byValue = secret("apiKey");
        byValue.setValueString("{memory.current.input}");
        var byPath = secret("token");
        byPath.setFromObjectPath("tokenResponse.access_token");
        var templatedName = secret("{context.slot}");
        templatedName.setValueString("x");

        assertDoesNotThrow(() -> SecretScopeValidation.validate(List.of(byValue, byPath, templatedName), "setProperties"));
    }

    @Test
    @DisplayName("typed values under scope secret are rejected, naming the instruction")
    void typedValuesRejected() {
        var object = secret("creds");
        object.setValueObject(Map.of("password", "x"));
        var e = assertThrows(IllegalArgumentException.class, () -> SecretScopeValidation.validate(List.of(object), "setProperties"));
        assertTrue(e.getMessage().startsWith("setProperties[0] ('creds') has scope 'secret'"), e.getMessage());

        for (var typed : List.of(withInt(), withList(), withBoolean(), withFloat())) {
            assertThrows(IllegalArgumentException.class, () -> SecretScopeValidation.validate(List.of(typed), "p"));
        }
    }

    private static PropertyInstruction withInt() {
        var i = secret("pin");
        i.setValueInt(1234);
        return i;
    }

    private static PropertyInstruction withList() {
        var i = secret("keys");
        i.setValueList(List.of("a"));
        return i;
    }

    private static PropertyInstruction withBoolean() {
        var i = secret("flag");
        i.setValueBoolean(true);
        return i;
    }

    private static PropertyInstruction withFloat() {
        var i = secret("ratio");
        i.setValueFloat(1.5f);
        return i;
    }

    @Test
    @DisplayName("convertToObject and an unembeddable literal name are rejected")
    void convertAndNameRejected() {
        var convert = secret("token");
        convert.setFromObjectPath("x");
        convert.setConvertToObject(true);
        assertThrows(IllegalArgumentException.class, () -> SecretScopeValidation.validate(List.of(convert), "p"));

        var slashed = secret("tenant/apiKey");
        slashed.setValueString("x");
        assertThrows(IllegalArgumentException.class, () -> SecretScopeValidation.validate(List.of(slashed), "p"));
    }

    @Test
    @DisplayName("other scopes are untouched")
    void otherScopesIgnored() {
        var plain = new PropertyInstruction();
        plain.setName("tenant/name");
        plain.setValueObject(Map.of("a", 1));
        plain.setScope(Scope.conversation);
        assertDoesNotThrow(() -> SecretScopeValidation.validate(List.of(plain), "p"));
    }

    @Test
    @DisplayName("the property setter store rejects such a configuration on save")
    void propertySetterStoreValidates() {
        var bad = secret("pin");
        bad.setValueInt(1234);
        var setOnActions = new SetOnActions();
        setOnActions.setActions(List.of("a"));
        setOnActions.setSetProperties(List.of(bad));
        var config = new PropertySetterConfiguration();
        config.setSetOnActions(List.of(setOnActions));

        var store = new PropertySetterStore(mock(IResourceStorageFactory.class), mock(IDocumentBuilder.class)) {
            void check(PropertySetterConfiguration c) {
                validate(c);
            }
        };
        var e = assertThrows(IllegalArgumentException.class, () -> store.check(config));
        assertTrue(e.getMessage().startsWith("setOnActions[0].setProperties[0]"), e.getMessage());
    }

    @Test
    @DisplayName("the apicalls store rejects a secret post-response instruction that cannot be vaulted")
    void apiCallsStoreValidates() {
        var bad = secret("claims");
        bad.setFromObjectPath("resp.claims");
        bad.setConvertToObject(true);
        var postResponse = new HttpPostResponse();
        postResponse.setPropertyInstructions(List.of(bad));
        var call = new ApiCall();
        call.setPostResponse(postResponse);
        var config = new ApiCallsConfiguration();
        config.setHttpCalls(List.of(call));

        var store = new ApiCallsStore(mock(IResourceStorageFactory.class), mock(IDocumentBuilder.class)) {
            void check(ApiCallsConfiguration c) {
                validate(c);
            }
        };
        var e = assertThrows(IllegalArgumentException.class, () -> store.check(config));
        assertTrue(e.getMessage().startsWith("httpCalls[0].postResponse.propertyInstructions[0]"), e.getMessage());
    }
}
