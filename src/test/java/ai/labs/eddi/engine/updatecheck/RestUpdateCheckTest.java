/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.updatecheck;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RestUpdateCheckTest {

    private final UpdateCheckService service = mock(UpdateCheckService.class);
    private final RestUpdateCheck sut = new RestUpdateCheck(service, mock(SecurityIdentity.class));

    @Test
    @DisplayName("a missing body or field is a 400, never a silent switch-off")
    void missingBodyIsRejected() {
        assertThrows(BadRequestException.class, () -> sut.update(null));
        assertThrows(BadRequestException.class, () -> sut.update(new IRestUpdateCheck.Update(null)));

        verify(service, never()).setUiEnabled(anyBoolean(), any());
    }

    @Test
    @DisplayName("an explicit value is stored")
    void explicitValueStored() {
        sut.update(new IRestUpdateCheck.Update(false));

        verify(service).setUiEnabled(false, null);
    }
}
