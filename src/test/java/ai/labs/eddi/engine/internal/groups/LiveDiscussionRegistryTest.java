/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LiveDiscussionRegistry} (Wave 0, F1).
 * <p>
 * The property that matters most is identity, not equality: {@link #get} must
 * return the exact instance {@link #register} was given, not a copy — that is
 * the whole reason this class exists (so a tool's mutation is visible to the
 * loop's next whole-document persist without a second store round trip). Every
 * test here asserts {@code ==}, never just {@code equals}, for that reason.
 *
 * @author tests
 */
class LiveDiscussionRegistryTest {

    private final LiveDiscussionRegistry registry = new LiveDiscussionRegistry();

    private GroupConversation gc(String id) {
        var gc = new GroupConversation();
        gc.setId(id);
        return gc;
    }

    @Test
    void get_unknownId_returnsEmpty() {
        assertTrue(registry.get("no-such-gc").isEmpty());
    }

    @Test
    void register_thenGet_returnsTheExactSameInstance() {
        var gc = gc("gc-1");

        registry.register(gc);

        var found = registry.get("gc-1");
        assertTrue(found.isPresent());
        assertSame(gc, found.get(), "must be the identical instance, not a copy or a deserialized clone");
    }

    @Test
    void mutationOnTheRegisteredInstance_isVisibleThroughGet() {
        var gc = gc("gc-1");
        registry.register(gc);

        gc.setCurrentPhaseName("EXECUTE");

        assertEquals("EXECUTE", registry.get("gc-1").get().getCurrentPhaseName(),
                "a tool mutating the registered instance must be visible without re-registering");
    }

    @Test
    void unregister_removesIt() {
        registry.register(gc("gc-1"));

        registry.unregister("gc-1");

        assertTrue(registry.get("gc-1").isEmpty());
    }

    @Test
    void unregister_unknownId_doesNotThrow() {
        assertDoesNotThrow(() -> registry.unregister("never-registered"));
    }

    @Test
    void register_sameIdTwice_replacesRatherThanAccumulating() {
        var first = gc("gc-1");
        var second = gc("gc-1");
        registry.register(first);

        registry.register(second);

        assertSame(second, registry.get("gc-1").get(),
                "a resume re-registering under the same id (its resumed GroupConversation) must supersede the stale pre-pause instance");
    }

    @Test
    void multipleDiscussions_areIndependentlyTracked() {
        var a = gc("gc-a");
        var b = gc("gc-b");
        registry.register(a);
        registry.register(b);

        assertSame(a, registry.get("gc-a").get());
        assertSame(b, registry.get("gc-b").get());

        registry.unregister("gc-a");

        assertTrue(registry.get("gc-a").isEmpty());
        assertSame(b, registry.get("gc-b").get(), "unregistering one discussion must not disturb another");
    }
}
