/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A CDI {@link Event} for tests outside the container: records every
 * synchronously fired payload, in order.
 */
public class RecordingEvent<T> implements Event<T> {

    private final List<T> fired = new CopyOnWriteArrayList<>();

    /** What was fired, in order. */
    public List<T> fired() {
        return List.copyOf(fired);
    }

    @Override
    public void fire(T event) {
        fired.add(event);
    }

    @Override
    public <U extends T> CompletionStage<U> fireAsync(U event) {
        throw new UnsupportedOperationException("the stores fire synchronously");
    }

    @Override
    public <U extends T> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
        throw new UnsupportedOperationException("the stores fire synchronously");
    }

    @Override
    public Event<T> select(Annotation... qualifiers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <U extends T> Event<U> select(Class<U> subtype, Annotation... qualifiers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <U extends T> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        throw new UnsupportedOperationException();
    }
}
