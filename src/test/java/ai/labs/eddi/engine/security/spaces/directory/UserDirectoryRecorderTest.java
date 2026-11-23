/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.directory;

import ai.labs.eddi.engine.org.OrgContext;
import ai.labs.eddi.engine.org.OrgId;
import ai.labs.eddi.engine.org.OrgTestRequests;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserDirectoryRecorderTest {

    private final UserDirectory directory = mock(UserDirectory.class);
    private final ContainerRequestContext request = mock(ContainerRequestContext.class);

    @Test
    void recordsTheCallerBoundToTheRequestOrg() {
        when(directory.isActive()).thenReturn(true);
        AtomicReference<Optional<OrgId>> bound = new AtomicReference<>();
        doAnswer(i -> {
            bound.set(OrgContext.current());
            return null;
        }).when(directory).recordCurrentCaller();

        new UserDirectoryRecorder(directory, OrgTestRequests.singleMode()).filter(request);

        assertEquals(Optional.of(OrgId.DEFAULT), bound.get());
    }

    @Test
    void resolvesNoOrgWhileTheDirectoryIsOff() {
        var refusing = OrgTestRequests.refusing();

        new UserDirectoryRecorder(directory, refusing).filter(request);

        assertEquals(0, refusing.resolutions());
        verify(directory, never()).recordCurrentCaller();
    }

    @Test
    void aCallerWithoutAnOrgIsNotRecordedAndNotRefused() {
        when(directory.isActive()).thenReturn(true);
        var refusing = OrgTestRequests.refusing();

        assertDoesNotThrow(() -> new UserDirectoryRecorder(directory, refusing).filter(request));

        assertEquals(1, refusing.resolutions());
        verify(directory, never()).recordCurrentCaller();
    }
}
