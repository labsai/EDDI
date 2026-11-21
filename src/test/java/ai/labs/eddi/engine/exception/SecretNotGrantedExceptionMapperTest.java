/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import ai.labs.eddi.secrets.SecretResolver;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("a vault grant refusal reaches a REST caller as 403, a missing secret as 400")
class SecretNotGrantedExceptionMapperTest {

    private final SecretNotGrantedExceptionMapper mapper = new SecretNotGrantedExceptionMapper();

    @Test
    void refusalIsForbiddenWithTheMessage() {
        Response response = mapper.toResponse(new SecretResolver.SecretNotGrantedException("Vault secret ${vault:k} is not resolved for agent 'b'"));
        assertEquals(403, response.getStatus());
        assertEquals("Vault secret ${vault:k} is not resolved for agent 'b'", response.getEntity());
    }

    @Test
    void missingSecretIsABadRequest() {
        Response response = mapper.toResponse(new SecretResolver.SecretMissingException("${vault:k}, which does not exist"));
        assertEquals(400, response.getStatus());
    }
}
