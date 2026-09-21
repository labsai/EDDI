/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding E6(2): configuration stores had no write-time validation hook, so a
 * structurally broken document was persisted and only blew up at agent-deploy
 * time. {@link AbstractResourceStore#validate(Object)} closes that gap.
 *
 * <p>
 * Two properties are load-bearing and are asserted here:
 * </p>
 * <ol>
 * <li>{@code create} and {@code update} run the hook <em>before</em> anything
 * reaches storage — a rejected document must leave no trace.</li>
 * <li>The default implementation is a no-op, so every store that has not opted
 * in behaves exactly as it did before.</li>
 * </ol>
 */
@DisplayName("AbstractResourceStore — write-time validate() hook")
class AbstractResourceStoreValidateHookTest {

    /** Store that refuses any document whose payload is blank. */
    private static class ValidatingStore extends AbstractResourceStore<String> {
        private final List<String> validated = new ArrayList<>();

        ValidatingStore(HistorizedResourceStore<String> delegate) {
            super(delegate);
        }

        @Override
        protected void validate(String content) {
            validated.add(content);
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("payload must not be blank");
            }
        }
    }

    /** Store that does not opt in — must keep the pre-existing behaviour. */
    private static class PlainStore extends AbstractResourceStore<String> {
        PlainStore(HistorizedResourceStore<String> delegate) {
            super(delegate);
        }
    }

    @SuppressWarnings("unchecked")
    private static HistorizedResourceStore<String> mockDelegate() {
        return mock(HistorizedResourceStore.class);
    }

    @Test
    @DisplayName("create rejects an invalid document before it reaches storage")
    void createRejectsInvalidDocument() throws Exception {
        var delegate = mockDelegate();
        var store = new ValidatingStore(delegate);

        var thrown = assertThrows(IllegalArgumentException.class, () -> store.create("  "));

        assertEquals("payload must not be blank", thrown.getMessage());
        assertEquals(List.of("  "), store.validated, "validate() must actually have been consulted");
        verify(delegate, never()).create(any());
    }

    @Test
    @DisplayName("update rejects an invalid document before it reaches storage")
    void updateRejectsInvalidDocument() throws Exception {
        var delegate = mockDelegate();
        var store = new ValidatingStore(delegate);

        var thrown = assertThrows(IllegalArgumentException.class, () -> store.update("id1", 3, "  "));

        assertEquals("payload must not be blank", thrown.getMessage());
        verify(delegate, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("a valid document still reaches storage on create and update")
    void validDocumentIsPersisted() throws Exception {
        var delegate = mockDelegate();
        when(delegate.update("id1", 3, "payload")).thenReturn(4);
        var store = new ValidatingStore(delegate);

        store.create("payload");
        assertEquals(4, store.update("id1", 3, "payload").intValue());

        assertEquals(List.of("payload", "payload"), store.validated);
        verify(delegate).create("payload");
        verify(delegate).update("id1", 3, "payload");
    }

    @Test
    @DisplayName("stores that do not override validate() are unaffected")
    void defaultHookIsANoOp() throws Exception {
        var delegate = mockDelegate();
        var store = new PlainStore(delegate);

        // A blank payload is nonsense, but nothing opted in — it must still be stored.
        store.create("  ");
        store.update("id1", 3, "  ");

        verify(delegate).create("  ");
        verify(delegate).update("id1", 3, "  ");
    }
}
