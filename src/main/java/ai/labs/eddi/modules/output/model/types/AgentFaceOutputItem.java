/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;

import java.util.Objects;

public class AgentFaceOutputItem extends OutputItem {
    private String uri;
    private String alt;
    private int delay;

    public AgentFaceOutputItem() {
        initType();
    }

    public AgentFaceOutputItem(String uri, String alt, int delay) {
        initType();
        this.uri = uri;
        this.alt = alt;
        this.delay = delay;
    }

    @Override
    protected void initType() {
        super.type = "agentFace";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        AgentFaceOutputItem that = (AgentFaceOutputItem) o;
        return delay == that.delay && Objects.equals(uri, that.uri) && Objects.equals(alt, that.alt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, alt, delay);
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getAlt() {
        return alt;
    }

    public void setAlt(String alt) {
        this.alt = alt;
    }

    public int getDelay() {
        return delay;
    }

    public void setDelay(int delay) {
        this.delay = delay;
    }
}
