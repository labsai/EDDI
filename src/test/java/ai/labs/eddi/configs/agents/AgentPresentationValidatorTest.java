/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.model.AgentAppearance;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.TitleGeneration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AgentPresentationValidator")
class AgentPresentationValidatorTest {

    private static AgentConfiguration withAppearance(AgentAppearance appearance) {
        var configuration = new AgentConfiguration();
        configuration.setAppearance(appearance);
        return configuration;
    }

    private static void assertInvalid(AgentConfiguration configuration, String fieldInMessage) {
        var e = assertThrows(IllegalArgumentException.class, () -> AgentPresentationValidator.validate(configuration));
        assertTrue(e.getMessage().contains(fieldInMessage), e.getMessage());
    }

    @Test
    @DisplayName("absent fields and a complete valid appearance pass")
    void valid() {
        assertDoesNotThrow(() -> AgentPresentationValidator.validate(new AgentConfiguration()));
        var appearance = new AgentAppearance();
        appearance.setDisplayName("Ava");
        appearance.setAvatar("https://cdn.example.com/a.png?v=2");
        appearance.setAccentColor("#4F46e5");
        appearance.setStarters(List.of("Track my order"));
        assertDoesNotThrow(() -> AgentPresentationValidator.validate(withAppearance(appearance)));

        appearance.setAccentColor("#abc");
        appearance.setAvatar("data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[1024]));
        assertDoesNotThrow(() -> AgentPresentationValidator.validate(withAppearance(appearance)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"red", "#12345", "#1234567", "4f46e5", "#ggg", "rgb(1,2,3)", "#fff;background:url(x)"})
    @DisplayName("accentColor must be #rgb or #rrggbb")
    void accentColor(String color) {
        var appearance = new AgentAppearance();
        appearance.setAccentColor(color);
        assertInvalid(withAppearance(appearance), "appearance.accentColor");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://cdn.example.com/a.png", "javascript:alert(1)", "//cdn.example.com/a.png", "/static/a.png",
            "https://user:pass@cdn.example.com/a.png", "data:image/svg+xml;base64,PHN2Zz4=", "data:text/html;base64,PGI+",
            "data:image/png,rawbytes", "data:image/png;base64,***", "ftp://example.com/a.png", "https:///a.png"})
    @DisplayName("avatar must be an https URL without credentials, or a raster data:image URI")
    void avatar(String avatar) {
        var appearance = new AgentAppearance();
        appearance.setAvatar(avatar);
        assertInvalid(withAppearance(appearance), "appearance.avatar");
    }

    @Test
    @DisplayName("an inline avatar is capped in size")
    void avatarSize() {
        var appearance = new AgentAppearance();
        appearance.setAvatar("data:image/png;base64,"
                + Base64.getEncoder().encodeToString(new byte[AgentPresentationValidator.MAX_AVATAR_DATA_BYTES + 3]));
        assertInvalid(withAppearance(appearance), "KB");
    }

    @Test
    @DisplayName("displayName: not blank, capped, no control characters")
    void displayName() {
        var appearance = new AgentAppearance();
        appearance.setDisplayName(" ");
        assertInvalid(withAppearance(appearance), "appearance.displayName");
        appearance.setDisplayName("x".repeat(AgentPresentationValidator.MAX_DISPLAY_NAME_LENGTH + 1));
        assertInvalid(withAppearance(appearance), "appearance.displayName");
        appearance.setDisplayName("Ava‮");
        assertInvalid(withAppearance(appearance), "appearance.displayName");
    }

    @Test
    @DisplayName("starters: capped in count and length, none blank")
    void starters() {
        var appearance = new AgentAppearance();
        appearance.setStarters(Collections.nCopies(AgentPresentationValidator.MAX_STARTERS + 1, "Hi"));
        assertInvalid(withAppearance(appearance), "appearance.starters");
        appearance.setStarters(List.of("x".repeat(AgentPresentationValidator.MAX_STARTER_LENGTH + 1)));
        assertInvalid(withAppearance(appearance), "appearance.starters[0]");
        var withNull = new ArrayList<String>();
        withNull.add(null);
        appearance.setStarters(withNull);
        assertInvalid(withAppearance(appearance), "appearance.starters[0]");
    }

    @Test
    @DisplayName("titleGeneration: provider and model required when enabled, bounded length and instructions")
    void titleGeneration() {
        var configuration = new AgentConfiguration();
        var generation = new TitleGeneration();
        configuration.setTitleGeneration(generation);
        assertDoesNotThrow(() -> AgentPresentationValidator.validate(configuration), "disabled needs nothing");

        generation.setEnabled(true);
        assertInvalid(configuration, "titleGeneration.llmProvider");
        generation.setLlmProvider("openai");
        generation.setLlmModel("gpt-4o-mini");
        assertDoesNotThrow(() -> AgentPresentationValidator.validate(configuration));

        generation.setMaxLength(5);
        assertInvalid(configuration, "titleGeneration.maxLength");
        generation.setMaxLength(60);
        generation.setInstructions("x".repeat(AgentPresentationValidator.MAX_TITLE_INSTRUCTIONS_LENGTH + 1));
        assertInvalid(configuration, "titleGeneration.instructions");
        generation.setInstructions(null);
        generation.setInputPricePer1M(-1.0);
        assertInvalid(configuration, "prices");
    }
}
