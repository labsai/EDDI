/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.impl.IProviderCooldownStore.Cooldown;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What both backends of {@link IProviderCooldownStore} must do alike. */
public interface ProviderCooldownStoreContract {

    /** A store with no rows. */
    IProviderCooldownStore cooldownStore();

    String KEY = "gemini|default|gemini-embedding-2|key:0123456789abcdef";

    @Test
    @DisplayName("an unknown quota has no cooldown")
    default void unknownQuotaHasNone() {
        assertTrue(cooldownStore().read("never-throttled").isEmpty());
    }

    @Test
    @DisplayName("a cooldown only ever moves later, and keeps the kind of the time that wins")
    default void cooldownOnlyMovesLater() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant later = now.plus(Duration.ofHours(8));
        cooldownStore().extend(KEY, later, true);
        cooldownStore().extend(KEY, now.plusSeconds(30), false);

        Cooldown cooldown = cooldownStore().read(KEY).orElseThrow();
        assertEquals(later, cooldown.until(), "an earlier refusal must not shorten a daily stop");
        assertTrue(cooldown.daily());

        Instant laterStill = later.plus(Duration.ofHours(1));
        cooldownStore().extend(KEY, laterStill, false);
        cooldown = cooldownStore().read(KEY).orElseThrow();
        assertEquals(laterStill, cooldown.until());
        assertFalse(cooldown.daily(), "the later refusal's kind replaces the earlier one's");
    }
}
