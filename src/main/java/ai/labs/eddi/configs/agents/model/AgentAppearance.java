/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.model;

import java.util.ArrayList;
import java.util.List;

/**
 * How an agent presents itself to the people chatting with it: the name, avatar
 * and colour a chat window shows, and the conversation starters it offers
 * before the first message. Every field is optional; absent means the client's
 * own default (the agent's descriptor name, no avatar, the deployment's theme,
 * no starters).
 * <p>
 * Pure presentation, served to anyone who may use the agent by {@code GET
 * /agents/{agentId}/profile}. It is checked when the agent is saved (see
 * {@code AgentPresentationValidator}): the avatar in particular is rendered by
 * browsers as an {@code <img>}, so it is limited to an {@code https} URL or a
 * small inline image.
 *
 * @since 6.7.0
 */
public class AgentAppearance {

    private String displayName;
    private String avatar;
    private String accentColor;
    private List<String> starters = new ArrayList<>();

    /**
     * The name a chat window shows for the agent; absent shows the agent's own
     * (descriptor) name.
     */
    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /**
     * The agent's picture: an {@code https} URL, or a {@code data:image/png},
     * {@code jpeg}, {@code gif} or {@code webp} base64 URI of limited size. It is
     * fetched by the viewer's browser, never by EDDI.
     */
    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    /** A CSS hex colour, {@code #rgb} or {@code #rrggbb}, used as the accent. */
    public String getAccentColor() {
        return accentColor;
    }

    public void setAccentColor(String accentColor) {
        this.accentColor = accentColor;
    }

    /**
     * Messages offered as one-click starters on an empty conversation, in order. A
     * starter is sent as the user's message exactly as written.
     */
    public List<String> getStarters() {
        return starters;
    }

    public void setStarters(List<String> starters) {
        this.starters = starters;
    }
}
