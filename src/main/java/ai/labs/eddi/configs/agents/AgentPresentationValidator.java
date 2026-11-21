/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.model.AgentAppearance;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.TitleGeneration;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Save-time checks of an agent's {@code appearance} and
 * {@code titleGeneration}, run by the agent store on every write path — REST,
 * ZIP import, agent sync, setup — so no path can store a value a chat client
 * would have to defend against. A failure is an
 * {@link IllegalArgumentException} naming the field, which the REST layer
 * answers with 400.
 *
 * <h3>Why the avatar is narrow</h3> The avatar is rendered by every viewer's
 * browser as an {@code <img>} on pages EDDI serves, so it is limited to what is
 * safe there: an {@code https} URL (no credentials in it, fetched by the
 * viewer, never by the server, so there is nothing to forge server-side), or a
 * small base64 {@code data:} URI of a raster image. SVG is refused — harmless
 * inside {@code <img>}, but a client that ever renders it another way would run
 * its scripts — and so is plain {@code http}, which the Chat UI's
 * {@code img-src} policy blocks by default anyway.
 */
public final class AgentPresentationValidator {

    /** The longest display name. */
    public static final int MAX_DISPLAY_NAME_LENGTH = 80;
    /** The longest avatar URL. */
    public static final int MAX_AVATAR_URL_LENGTH = 2048;
    /** The largest inline avatar, decoded, in bytes. */
    public static final int MAX_AVATAR_DATA_BYTES = 64 * 1024;
    /** The most conversation starters. */
    public static final int MAX_STARTERS = 6;
    /** The longest conversation starter. */
    public static final int MAX_STARTER_LENGTH = 200;
    /** Bounds of {@code titleGeneration.maxLength}. */
    public static final int MIN_TITLE_LENGTH = 10;
    public static final int MAX_TITLE_LENGTH = 200;
    /** The longest custom title instruction. */
    public static final int MAX_TITLE_INSTRUCTIONS_LENGTH = 2000;

    private static final Pattern HEX_COLOR = Pattern.compile("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})");
    private static final Pattern DATA_IMAGE = Pattern.compile("data:image/(png|jpeg|gif|webp);base64,([A-Za-z0-9+/]+={0,2})");

    private AgentPresentationValidator() {
    }

    /**
     * @throws IllegalArgumentException
     *             naming the offending field
     */
    public static void validate(AgentConfiguration configuration) {
        if (configuration == null) {
            return;
        }
        validateAppearance(configuration.getAppearance());
        validateTitleGeneration(configuration.getTitleGeneration());
    }

    static void validateAppearance(AgentAppearance appearance) {
        if (appearance == null) {
            return;
        }
        String displayName = appearance.getDisplayName();
        if (displayName != null) {
            if (displayName.isBlank()) {
                throw invalid("appearance.displayName must not be blank; leave it out to use the agent's name");
            }
            if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
                throw invalid("appearance.displayName must be at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
            }
            if (hasControlCharacter(displayName)) {
                throw invalid("appearance.displayName must not contain control characters");
            }
        }
        if (appearance.getAccentColor() != null && !HEX_COLOR.matcher(appearance.getAccentColor()).matches()) {
            throw invalid("appearance.accentColor must be a CSS hex colour, #rgb or #rrggbb, got: " + appearance.getAccentColor());
        }
        if (appearance.getAvatar() != null) {
            validateAvatar(appearance.getAvatar());
        }
        validateStarters(appearance.getStarters());
    }

    static void validateAvatar(String avatar) {
        if (avatar.regionMatches(true, 0, "data:", 0, 5)) {
            var matcher = DATA_IMAGE.matcher(avatar);
            if (!matcher.matches()) {
                throw invalid("appearance.avatar as a data URI must be data:image/png, jpeg, gif or webp in base64");
            }
            // Bounded before decoding, so an oversized value is refused without allocating
            // it.
            String base64 = matcher.group(2);
            if ((long) base64.length() * 3 / 4 > MAX_AVATAR_DATA_BYTES + 2L) {
                throw invalid("appearance.avatar as a data URI must be at most " + MAX_AVATAR_DATA_BYTES / 1024 + " KB");
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                throw invalid("appearance.avatar is not valid base64");
            }
            if (decoded.length > MAX_AVATAR_DATA_BYTES) {
                throw invalid("appearance.avatar as a data URI must be at most " + MAX_AVATAR_DATA_BYTES / 1024 + " KB");
            }
            return;
        }
        if (avatar.length() > MAX_AVATAR_URL_LENGTH) {
            throw invalid("appearance.avatar must be at most " + MAX_AVATAR_URL_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(avatar);
        } catch (URISyntaxException e) {
            throw invalid("appearance.avatar is not a valid URL");
        }
        if (uri.getScheme() == null || !"https".equals(uri.getScheme().toLowerCase(Locale.ROOT)) || uri.getHost() == null
                || uri.getHost().isBlank()) {
            throw invalid("appearance.avatar must be an https URL or a data:image URI");
        }
        if (uri.getRawUserInfo() != null) {
            throw invalid("appearance.avatar must not carry credentials");
        }
    }

    static void validateStarters(List<String> starters) {
        if (starters == null) {
            return;
        }
        if (starters.size() > MAX_STARTERS) {
            throw invalid("appearance.starters may hold at most " + MAX_STARTERS + " starters");
        }
        for (int i = 0; i < starters.size(); i++) {
            String starter = starters.get(i);
            if (starter == null || starter.isBlank()) {
                throw invalid("appearance.starters[" + i + "] must not be blank");
            }
            if (starter.length() > MAX_STARTER_LENGTH) {
                throw invalid("appearance.starters[" + i + "] must be at most " + MAX_STARTER_LENGTH + " characters");
            }
        }
    }

    static void validateTitleGeneration(TitleGeneration titleGeneration) {
        if (titleGeneration == null) {
            return;
        }
        if (titleGeneration.getMaxLength() < MIN_TITLE_LENGTH || titleGeneration.getMaxLength() > MAX_TITLE_LENGTH) {
            throw invalid("titleGeneration.maxLength must be between " + MIN_TITLE_LENGTH + " and " + MAX_TITLE_LENGTH);
        }
        if (titleGeneration.getInstructions() != null && titleGeneration.getInstructions().length() > MAX_TITLE_INSTRUCTIONS_LENGTH) {
            throw invalid("titleGeneration.instructions must be at most " + MAX_TITLE_INSTRUCTIONS_LENGTH + " characters");
        }
        if (negative(titleGeneration.getInputPricePer1M()) || negative(titleGeneration.getOutputPricePer1M())) {
            throw invalid("titleGeneration prices must not be negative");
        }
        if (titleGeneration.isEnabled() && (isBlank(titleGeneration.getLlmProvider()) || isBlank(titleGeneration.getLlmModel()))) {
            throw invalid("titleGeneration.llmProvider and titleGeneration.llmModel are required when titleGeneration is enabled");
        }
    }

    private static boolean negative(Double value) {
        return value != null && (value.isNaN() || value < 0);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Control and format characters, except ZWNJ (U+200C) and ZWJ (U+200D):
     * Persian, the Indic scripts and emoji sequences need those two to render
     * correctly, while the other format characters (bidi overrides such as U+202E)
     * can disguise a name.
     */
    private static boolean hasControlCharacter(String value) {
        return value.codePoints().anyMatch(cp -> cp != 0x200C && cp != 0x200D
                && (Character.getType(cp) == Character.CONTROL || Character.getType(cp) == Character.FORMAT));
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
